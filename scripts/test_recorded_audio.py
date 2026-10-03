"""Recorded-audio gate contract tests; synthetic reports test validation only."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import check_recorded_audio as gate


class RecordedAudioContractTests(unittest.TestCase):
    SOURCE = "a" * 40

    def report(self):
        sample, pcm = gate.fixture()
        models = gate.model_specs()
        result = {"schema_version": 1, "source_commit": self.SOURCE, "status": "passed",
                  "runtime_versions": gate.contract()["runtime_versions"], "checks": []}
        for spec in gate.contract()["checks"]:
            result["checks"].append({**spec, "status": "passed", "sample_id": sample["id"],
                "input_sha256": gate.sha256(gate.variant_pcm(pcm, spec["variant"])),
                "recording_sha256": sample["sha256"], "model_files": models[spec["backend"]],
                "text": sample["text"], "raw_result": "synthetic validator unit-test fixture",
                "elapsed_ms": 10.0, **gate.score(sample["text"], sample["text"])})
        return result

    def test_fixture_is_human_pcm_with_both_independent_checksums(self):
        sample, pcm = gate.fixture()
        self.assertEqual(333920, len(pcm))
        self.assertEqual(sample["pcm_sha256"], gate.sha256(pcm))
        self.assertEqual("CC-BY-4.0", sample["license"])
        self.assertNotEqual(bytes(len(pcm)), pcm)

    def test_production_weight_specs_are_not_empty_or_replaced(self):
        specs = gate.model_specs()
        self.assertEqual((8, 3), (len(specs["moonshine"]), len(specs["whisper"])))
        self.assertEqual("base.en-encoder.int8.onnx", specs["whisper"][0]["name"])
        self.assertEqual(64, len(specs["moonshine"][0]["sha256"]))

    def test_complete_exact_revision_report_passes(self):
        gate.validate_report(self.report(), self.SOURCE)

    def test_required_checks_reject_missing_duplicate_unknown_failed_and_skipped(self):
        for mutation in ("missing", "duplicate", "unknown", "failed", "skipped"):
            with self.subTest(mutation=mutation):
                report = self.report()
                if mutation == "missing":
                    report["checks"].pop()
                elif mutation == "duplicate":
                    report["checks"][-1] = copy.deepcopy(report["checks"][0])
                elif mutation == "unknown":
                    report["checks"][-1]["name"] = "unrequested_check"
                else:
                    report["checks"][-1]["status"] = mutation
                with self.assertRaises(ValueError):
                    gate.validate_report(report, self.SOURCE)

    def test_source_runtime_recording_and_weight_substitutions_are_rejected(self):
        for mutation in ("source", "runtime", "recording", "pcm", "weights", "backend"):
            with self.subTest(mutation=mutation):
                report = self.report()
                check = report["checks"][0]
                if mutation == "source":
                    report["source_commit"] = "b" * 40
                elif mutation == "runtime":
                    report["runtime_versions"]["moonshine-voice"] = "unverified"
                elif mutation == "recording":
                    check["recording_sha256"] = "0" * 64
                elif mutation == "pcm":
                    check["input_sha256"] = "0" * 64
                elif mutation == "weights":
                    check["model_files"][0]["sha256"] = "0" * 64
                else:
                    check["backend"] = "substitute"
                with self.assertRaises(ValueError):
                    gate.validate_report(report, self.SOURCE)

    def test_missing_boundary_words_fail_even_below_overall_wer_limit(self):
        for remove in (0, -1):
            report = self.report()
            text = report["checks"][0]["text"].split()
            text.pop(remove)
            item = report["checks"][0]
            item["text"] = " ".join(text)
            item.update(gate.score(gate.fixture()[0]["text"], item["text"]))
            self.assertLess(item["word_error_rate"], .2)
            with self.assertRaises(ValueError):
                gate.validate_report(report, self.SOURCE)

    def test_raw_score_cannot_be_replaced_with_a_better_claimed_score(self):
        report = self.report()
        report["checks"][0]["text"] = "MISTER unrelated wrong response GOSPEL"
        with self.assertRaises(ValueError):
            gate.validate_report(report, self.SOURCE)

    def test_native_raw_result_and_finite_timing_are_mandatory(self):
        for key, value in (("raw_result", ""), ("elapsed_ms", 0), ("elapsed_ms", float("nan"))):
            report = self.report()
            report["checks"][0][key] = value
            with self.assertRaises(ValueError):
                gate.validate_report(report, self.SOURCE)

    def test_abbreviation_normalization_preserves_boundary_and_edit_scoring(self):
        result = gate.score("MISTER QUILTER IS THE APOSTLE", "Mr. Quilter is the apostle.")
        self.assertEqual(0, result["word_error_rate"])
        self.assertTrue(result["first_word_correct"])
        self.assertTrue(result["last_word_correct"])
        self.assertEqual(.2, gate.score("one two three four five", "one two wrong four five")["word_error_rate"])

    def test_trailing_silence_does_not_change_recorded_speech_samples(self):
        _, pcm = gate.fixture()
        result = gate.variant_pcm(pcm, "trailing_silence")
        self.assertEqual(pcm, result[:len(pcm)])
        self.assertEqual(bytes(32000), result[len(pcm):])

    def test_truncated_corrupt_and_oversized_downloads_never_replace_cache(self):
        for contents in (b"", b"bad", b"too-long"):
            with self.subTest(contents=contents), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "model.ort"
                target.write_bytes(b"previous verified cache")
                with mock.patch("urllib.request.urlopen", return_value=io.BytesIO(contents)):
                    with self.assertRaises(ValueError):
                        gate.download("https://invalid.test/model", target, 4, gate.sha256(b"good"))
                self.assertEqual(b"previous verified cache", target.read_bytes())
                self.assertFalse(target.with_suffix(".ort.part").exists())

    def test_valid_download_installs_only_after_hash_and_length_match(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "model.ort"
            with mock.patch("urllib.request.urlopen", return_value=io.BytesIO(b"good")):
                gate.download("https://invalid.test/model", target, 4, gate.sha256(b"good"))
            self.assertEqual(b"good", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
