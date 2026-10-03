"""Failure evidence must not be mistaken for an intentional external process death."""
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch

from android import Device, instrumentation_results, interrupted_results


def interrupted(boundary="process_kill"):
    return ("INSTRUMENTATION_STATUS: class=Suite\nINSTRUMENTATION_STATUS: test=phase\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: jarvisBoundary={boundary}\nINSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")


class PhaseEvidenceTest(unittest.TestCase):
    def test_boundary_reuses_exported_evidence_without_a_second_ui_automation_session(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            calls = []

            def pull(*args, **kwargs):
                calls.append(args)
                self.assertEqual("pull", args[0])
                target = Path(args[2])
                target.write_bytes(b"\x89PNG\r\n\x1a\nfixture" if target.suffix == ".png" else b"<hierarchy />")

            with patch.object(device, "run", side_effect=pull), patch.object(device, "snapshot", side_effect=AssertionError("Concurrent UiAutomation")):
                device.copy_phase_boundary("testPhase", "jarvis-verification-123", "process_kill")
            self.assertEqual(2, len(calls))
            self.assertEqual("/sdcard/Download/jarvis-verification-123/testPhase.png", calls[0][1])
            self.assertEqual("/sdcard/Download/jarvis-verification-123/testPhase.xml", calls[1][1])

    def test_corrupt_exported_boundary_evidence_prevents_interruption(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            with patch.object(device, "run", side_effect=lambda *args, **kwargs: Path(args[2]).write_bytes(b"invalid")):
                with self.assertRaisesRegex(RuntimeError, "Invalid pre-interruption screenshot"):
                    device.copy_phase_boundary("testPhase", "jarvis-verification-123", "process_kill")

    def test_actual_declared_boundary_is_an_interruption_not_a_junit_pass(self):
        raw = interrupted()
        self.assertTrue(interrupted_results(raw, "phase", "Suite", "process_kill")["passed"])
        self.assertFalse(instrumentation_results(raw, ["phase"], "Suite")["passed"])

    def test_missing_wrong_duplicate_or_late_boundary_never_passes(self):
        for raw in ("", interrupted("permission_revoke"), interrupted() + interrupted(),
                    interrupted().replace("class=Suite", "class=Wrong"),
                    interrupted().replace("test=phase", "test=Other"),
                    interrupted().replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                    interrupted() + "OK (1 test)\n", interrupted() + "FAILURES!!!\n",
                    interrupted().replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: nope", 1)):
            with self.subTest(raw=raw):
                self.assertFalse(interrupted_results(raw, "phase", "Suite", "process_kill")["passed"])

    def test_success_footer_count_and_unique_contract_are_required(self):
        raw = "INSTRUMENTATION_STATUS: class=Suite\nINSTRUMENTATION_STATUS: test=phase\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
        self.assertTrue(instrumentation_results(raw, ["phase"], "Suite")["passed"])
        for output, expected in ((raw.replace("(1 test)", "(999 tests)"), ["phase"]),
                                 (raw + "OK (1 test)\n", ["phase"]), (raw, ["phase", "phase"]),
                                 (raw.replace("CODE: 0", "CODE: invalid"), ["phase"]), ("OK (0 tests)\n", [])):
            with self.subTest(output=output, expected=expected):
                self.assertFalse(instrumentation_results(output, expected, "Suite")["passed"])


if __name__ == "__main__":
    unittest.main()
