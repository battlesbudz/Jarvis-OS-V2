"""Failure evidence must not be mistaken for an intentional external process death."""
from contextlib import redirect_stdout
import io
import json
import shlex
import subprocess
from types import SimpleNamespace
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch

from android import Device, PACKAGE, instrumentation_results, interrupted_results, sha256, verify
from profiles import load_profiles


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


class SnapshotTransportTest(unittest.TestCase):
    def exercise(self, fault=None):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            png = b"\x89PNG\r\n\x1a\nfresh capture"
            stale = b"\x89PNG\r\n\x1a\nprevious capture"
            warning = "[Warning] Multiple displays were found, but no display id was specified!\n"
            remote = {"/sdcard/jarvis-screen.png": stale}
            target = folder / "current.png"
            target.write_bytes(stale)
            calls = []

            class ScreenshotDevice(Device):
                def run(self, *argv, **kwargs):
                    calls.append(argv)
                    if argv[0] == "pull":
                        if fault == "pull" or argv[1] not in remote:
                            raise RuntimeError("Capture file could not be pulled")
                        Path(argv[2]).write_bytes(remote[argv[1]])
                        return "pulled"
                    if argv[0] != "shell":
                        raise AssertionError("Diagnostic bytes must not be captured as an image stream")
                    command = shlex.split(argv[1])
                    if command == ["rm", "-f", "/sdcard/jarvis-screen.png"]:
                        if fault == "remove":
                            raise RuntimeError("Could not remove previous capture")
                        remote.pop(command[2], None)
                        return ""
                    if command[:2] == ["sh", "-c"]:
                        if len(command) != 3 or command[2] != "screencap -p /sdcard/jarvis-screen.png 2>&1":
                            raise AssertionError("The diagnostic redirection must be shell syntax")
                        if fault == "capture":
                            raise RuntimeError("Screenshot capture failed")
                        if fault != "missing":
                            remote["/sdcard/jarvis-screen.png"] = warning.encode() + png if fault == "malformed" else png
                        return warning
                    if command == ["uiautomator", "dump", "/sdcard/jarvis-window.xml"]:
                        if fault == "hierarchy":
                            raise RuntimeError("Hierarchy capture failed")
                        return "dumped"
                    if command == ["cat", "/sdcard/jarvis-window.xml"]:
                        return "<hierarchy />"
                    raise AssertionError(f"Unexpected snapshot command: {command}")

            device = ScreenshotDevice("emulator-5554", folder)
            result = error = None
            try:
                result = device.snapshot("current")
            except RuntimeError as failure:
                error = failure
            diagnostic = folder / "current-screencap.txt"
            return {"xml": result, "error": error, "calls": calls,
                    "image": target.read_bytes() if target.exists() else None,
                    "diagnostic": diagnostic.read_text() if diagnostic.exists() else None,
                    "expected_image": png, "warning": warning, "remote": remote}

    def test_multidisplay_warning_is_retained_separately_from_fresh_png(self):
        result = self.exercise()
        self.assertIsNone(result["error"])
        self.assertEqual("<hierarchy />", result["xml"])
        self.assertEqual(result["expected_image"], result["image"])
        self.assertEqual(result["warning"], result["diagnostic"])

    def test_failed_or_missing_capture_cannot_reuse_a_previous_local_or_remote_image(self):
        for fault in ("remove", "capture", "missing"):
            with self.subTest(fault=fault):
                result = self.exercise(fault)
                self.assertIsInstance(result["error"], RuntimeError)
                self.assertIsNone(result["xml"])
                self.assertIsNone(result["image"])
                if fault != "remove":
                    self.assertNotIn("/sdcard/jarvis-screen.png", result["remote"])
                if fault == "capture":
                    self.assertIn("Screenshot capture failed", result["diagnostic"])

    def test_prefixed_or_malformed_pulled_image_is_rejected_without_trimming(self):
        result = self.exercise("malformed")
        self.assertRegex(str(result["error"]), "Invalid captured screenshot")
        self.assertIsNone(result["xml"])
        self.assertEqual(result["warning"].encode() + result["expected_image"], result["image"])
        self.assertFalse(any("uiautomator" in str(call) for call in result["calls"]))

    def test_pull_or_hierarchy_failure_remains_a_failed_snapshot(self):
        for fault in ("pull", "hierarchy"):
            with self.subTest(fault=fault):
                result = self.exercise(fault)
                self.assertIsInstance(result["error"], RuntimeError)
                self.assertIsNone(result["xml"])
                self.assertEqual(result["warning"], result["diagnostic"])


class InstallTransportTest(unittest.TestCase):
    def test_install_transport_retains_flags_and_deadline_on_every_required_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            for profile in load_profiles():
                transport = ["--no-streaming"] if profile["id"] == "29-phone-normal" else []
                for apk, flags in (("previous.apk", ()), ("tests.apk", ("-r", "-t")),
                                   ("candidate.apk", ("-r",))):
                    with self.subTest(profile=profile["id"], apk=apk), patch(
                            "android.subprocess.run", return_value=subprocess.CompletedProcess([], 0, b"Success", b"")) as run:
                        self.assertEqual("Success", device.install(apk, *flags, profile=profile))
                    run.assert_called_once_with(
                        ["adb", "-s", "emulator-5554", "install", *transport, *flags, apk],
                        capture_output=True, timeout=180)

    def test_transport_is_scoped_to_api29_software_and_install_failures_propagate(self):
        profile = next(p for p in load_profiles() if p["id"] == "29-phone-normal")
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            for changed in (dict(profile, api=30), dict(profile, acceleration="kvm")):
                with self.subTest(profile=changed), patch(
                        "android.subprocess.run", return_value=subprocess.CompletedProcess([], 0, b"Success", b"")) as run:
                    device.install("candidate.apk", "-r", profile=changed)
                self.assertEqual(["adb", "-s", "emulator-5554", "install", "-r", "candidate.apk"], run.call_args.args[0])
            with patch("android.subprocess.run", return_value=subprocess.CompletedProcess(
                    [], 1, b"", b"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")):
                with self.assertRaisesRegex(RuntimeError, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"):
                    device.install("candidate.apk", "-r", profile=profile)
            with patch("android.subprocess.run", side_effect=subprocess.TimeoutExpired("adb", 180)):
                with self.assertRaises(subprocess.TimeoutExpired):
                    device.install("candidate.apk", "-r", profile=profile)

    def exercise_upgrade(self, profile, *, reject_candidate=False):
        """Exercise the real controller's upgrade ordering, then deliberately stop."""
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            previous, candidate, tests = (folder / name for name in ("previous.apk", "candidate.apk", "tests.apk"))
            for apk in (previous, candidate, tests):
                apk.write_bytes(b"synthetic controller input")
            metadata = folder / "previous.json"
            metadata.write_text(json.dumps({"schema": 1, "sha256": sha256(previous), "build": 907,
                                            "tag": "audio-pr2-pr6-build.907", "repository": "battlesbudz/Jarvis-OS-V2"}))
            args = SimpleNamespace(out=str(folder / "evidence"), serial="emulator-5554", adb="adb",
                                   profile=profile["id"], apk=str(candidate), test_apk=str(tests),
                                   previous_apk=str(previous), previous_metadata=str(metadata),
                                   source_commit="a" * 40, pr_head="b" * 40, allow_emulator_reset=True)
            calls = []

            class UpgradeDevice(Device):
                updated = False

                def run(self, *argv, **kwargs):
                    calls.append((argv, kwargs))
                    if argv[0] == "install":
                        if argv[-1] == str(candidate):
                            if reject_candidate:
                                raise RuntimeError("adb install failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                            self.updated = True
                        return "Success"
                    if argv[0] != "shell":
                        return ""
                    command = shlex.split(argv[1])
                    if command[0] == "getprop":
                        return {"ro.kernel.qemu": "1", "ro.product.cpu.abilist": "arm64-v8a",
                                "ro.build.version.sdk": str(profile["api"]), "ro.build.fingerprint": "fixture",
                                "ro.dalvik.vm.native.bridge": "0"}[command[1]]
                    if command[0] == "getconf":
                        return str(profile["page_size"])
                    if command[:2] == ["dumpsys", "package"]:
                        return "versionCode=" + ("931" if self.updated else "907")
                    return "Success"

                def instrument(self, test_class, named_tests, evidence_folder, **kwargs):
                    calls.append((("instrument", *named_tests), kwargs))
                    return "synthetic upgrade phase", {"passed": True, "errors": []}, []

                def snapshot(self, label):
                    if label != "final":
                        raise RuntimeError("Controlled stop after upgrade; later Android gates are not simulated")
                    return "<hierarchy />"

            with patch("android.Device", UpgradeDevice), redirect_stdout(io.StringIO()):
                self.assertEqual(1, verify(args))
            report = json.loads((Path(args.out) / "report.json").read_text())
            self.assertFalse(report["passed"])
            installs = [(argv[1:], kwargs) for argv, kwargs in calls if argv[0] == "install"]
            transport = ("--no-streaming",) if profile["id"] == "29-phone-normal" else ()
            self.assertEqual([(transport + (str(previous),), {"timeout": 180}),
                              (transport + ("-r", "-t", str(tests)), {"timeout": 180}),
                              (transport + ("-r", str(candidate)), {"timeout": 180})], installs)
            return report, calls

    def test_controller_routes_all_upgrade_installs_without_clearing_seeded_storage(self):
        for profile in load_profiles():
            with self.subTest(profile=profile["id"]):
                report, calls = self.exercise_upgrade(profile)
                self.assertTrue(report["upgrade"]["passed"])
                self.assertFalse(report["upgrade"]["data_cleared_during_update"])
                phases = [index for index, (argv, _) in enumerate(calls) if argv[0] == "instrument"]
                self.assertEqual(2, len(phases))
                between = [argv for argv, _ in calls[phases[0] + 1:phases[1]]]
                self.assertEqual(1, sum(argv[0] == "install" for argv in between))
                self.assertFalse(any(argv[0] == "uninstall" or
                                     argv[0] == "shell" and shlex.split(argv[1])[:2] == ["pm", "clear"]
                                     for argv in between))
                self.assertEqual(907, report["upgrade"]["previous_version_code"])
                self.assertEqual(931, report["upgrade"]["candidate_version_code"])
                self.assertIn("Controlled stop after upgrade", report["errors"][0])

    def test_rejected_update_does_not_run_upgrade_verification_or_pass(self):
        for profile in load_profiles():
            with self.subTest(profile=profile["id"]):
                report, calls = self.exercise_upgrade(profile, reject_candidate=True)
                self.assertFalse(report["upgrade"]["passed"])
                self.assertEqual(1, sum(argv[0] == "instrument" for argv, _ in calls))
                self.assertIn("INSTALL_FAILED_UPDATE_INCOMPATIBLE", report["errors"][0])


if __name__ == "__main__":
    unittest.main()
