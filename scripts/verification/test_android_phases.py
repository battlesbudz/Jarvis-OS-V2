"""Failure evidence must not be mistaken for an intentional external process death."""
from contextlib import redirect_stdout
import base64
import io
import json
import shlex
import subprocess
import sys
from types import SimpleNamespace
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch
from xml.etree.ElementTree import ParseError

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


class SnapshotEvidenceTest(unittest.TestCase):
    PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fake_adb = self.root / 'adb'
        self.fake_adb.write_text(f'#!{sys.executable}\n' + '''import json,shlex,shutil,sys
from pathlib import Path
root=Path(__file__).parent
mode=json.loads((root/'mode.json').read_text())
args=sys.argv[3:]
with (root/'calls.jsonl').open('a') as log:
    log.write(json.dumps(args)+'\\n')
if args[0]=='pull':
    if mode.get('failure')=='pull':
        print('failed pull',file=sys.stderr);sys.exit(8)
    shutil.copyfile(root/'device.png',args[2])
    print('1 file pulled')
elif args[0]=='shell':
    command=shlex.split(args[1])
    if command==['rm','-f','/sdcard/jarvis-screen.png']:
        if mode.get('failure')=='remove':
            print('failed removal',file=sys.stderr);sys.exit(6)
        (root/'device.png').unlink(missing_ok=True)
    elif command==['rm','-f','/sdcard/jarvis-window.xml']:
        if mode.get('failure')=='hierarchy_remove':
            print('failed hierarchy removal',file=sys.stderr);sys.exit(10)
        (root/'device.xml').unlink(missing_ok=True)
    elif command[0]=='screencap':
        assert command==['screencap','-p','/sdcard/jarvis-screen.png']
        print('[Warning] Multiple displays were found')
        print('capture stderr diagnostic',file=sys.stderr)
        if mode.get('failure')=='capture':sys.exit(7)
        if mode.get('failure')!='missing':
            (root/'device.png').write_bytes(bytes.fromhex(mode['png']))
    elif command[:2]==['uiautomator','dump']:
        assert command[2]=='/sdcard/jarvis-window.xml'
        if mode.get('failure')=='hierarchy':
            print('failed hierarchy',file=sys.stderr);sys.exit(9)
        if mode.get('failure')=='hierarchy_missing':
            print('ERROR: null root node returned',file=sys.stderr);sys.exit(0)
        (root/'device.xml').write_text(mode.get('xml','<hierarchy />'))
        print('UI hierarchy dumped')
    elif command==['cat','/sdcard/jarvis-window.xml']:
        if not (root/'device.xml').exists():
            print('failed hierarchy read: no new device XML',file=sys.stderr);sys.exit(11)
        print((root/'device.xml').read_text())
    else:raise AssertionError(command)
else:raise AssertionError(args)
''')
        self.fake_adb.chmod(0o755)
        self.device = Device('emulator-5554', self.root / 'evidence', str(self.fake_adb))
        self.mode()

    def mode(self, **changes):
        (self.root / 'mode.json').write_text(json.dumps(dict(png=self.PNG.hex(), **changes)))

    def receipts(self):
        return [json.loads(line) for line in (self.device.out / 'commands.jsonl').read_text().splitlines()]

    def test_device_file_and_pull_preserve_png_bytes_and_both_diagnostic_streams(self):
        stale = '<hierarchy><node text="stale model" /></hierarchy>'
        fresh = '<hierarchy><node text="fresh model" /></hierarchy>'
        (self.root / 'device.xml').write_text(stale)
        (self.device.out / 'baseline.xml').write_text(stale)
        self.mode(xml=fresh)
        self.assertEqual(fresh + '\n', self.device.snapshot('baseline'))
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        self.assertEqual(fresh, (self.root / 'device.xml').read_text())
        self.assertEqual(fresh + '\n', (self.device.out / 'baseline.xml').read_text())
        receipts = self.receipts()
        self.assertEqual(['shell', 'shell', 'pull', 'shell', 'shell', 'shell'], [r['argv'][3] for r in receipts])
        self.assertEqual('rm -f /sdcard/jarvis-screen.png', receipts[0]['argv'][4])
        self.assertEqual('screencap -p /sdcard/jarvis-screen.png', receipts[1]['argv'][4])
        self.assertEqual(['/sdcard/jarvis-screen.png', str(self.device.out / 'baseline.png')], receipts[2]['argv'][4:])
        self.assertEqual('rm -f /sdcard/jarvis-window.xml', receipts[3]['argv'][4])
        self.assertEqual('uiautomator dump /sdcard/jarvis-window.xml', receipts[4]['argv'][4])
        self.assertEqual('cat /sdcard/jarvis-window.xml', receipts[5]['argv'][4])
        self.assertIn('Multiple displays', receipts[1]['stdout'])
        self.assertIn('capture stderr diagnostic', receipts[1]['stderr'])
        diagnostic = (self.device.out / 'baseline-screencap.txt').read_text()
        self.assertIn('Multiple displays', diagnostic)
        self.assertIn('capture stderr diagnostic', diagnostic)
        self.assertFalse(any('exec-out' in r['argv'] for r in receipts))
        self.assertTrue(all(r['exit'] == 0 for r in receipts))

    def test_capture_and_pull_failures_stop_before_hierarchy_and_retain_diagnostics(self):
        for failure, expected in (('remove', 1), ('capture', 2), ('pull', 3)):
            with self.subTest(failure=failure):
                for f in self.device.out.iterdir(): f.unlink()
                (self.device.out / 'baseline.png').write_bytes(self.PNG)
                self.mode(failure=failure)
                with self.assertRaisesRegex(RuntimeError, 'adb (shell|pull) failed'):
                    self.device.snapshot('baseline')
                receipts = self.receipts()
                self.assertEqual(expected, len(receipts))
                self.assertNotEqual(0, receipts[-1]['exit'])
                self.assertTrue(receipts[-1]['stderr'])
                self.assertFalse((self.device.out / 'baseline.png').exists())
                if failure != 'remove':
                    self.assertIn('capture stderr diagnostic', (self.device.out / 'baseline-screencap.txt').read_text())

    def test_missing_new_capture_cannot_reuse_a_previous_local_or_remote_png(self):
        (self.root / 'device.png').write_bytes(self.PNG)
        (self.device.out / 'baseline.png').write_bytes(self.PNG)
        self.mode(failure='missing')
        with self.assertRaisesRegex(RuntimeError, 'adb pull failed'):
            self.device.snapshot('baseline')
        self.assertFalse((self.root / 'device.png').exists())
        self.assertFalse((self.device.out / 'baseline.png').exists())
        self.assertEqual(3, len(self.receipts()))
        self.assertIn('Multiple displays', (self.device.out / 'baseline-screencap.txt').read_text())

    def test_hierarchy_command_failure_remains_a_failed_snapshot_with_valid_png_and_diagnostics(self):
        self.mode(failure='hierarchy')
        with self.assertRaisesRegex(RuntimeError, 'failed hierarchy'):
            self.device.snapshot('baseline')
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        self.assertEqual(5, len(self.receipts()))
        self.assertIn('Multiple displays', (self.device.out / 'baseline-screencap.txt').read_text())

    def test_zero_exit_dump_without_new_xml_cannot_reuse_stale_local_or_remote_hierarchy(self):
        stale = '<hierarchy><node text="previous model" /></hierarchy>'
        (self.root / 'device.xml').write_text(stale)
        (self.device.out / 'baseline.xml').write_text(stale)
        self.mode(failure='hierarchy_missing')
        with self.assertRaisesRegex(RuntimeError, 'failed hierarchy read: no new device XML'):
            self.device.snapshot('baseline')
        self.assertFalse((self.root / 'device.xml').exists())
        self.assertFalse((self.device.out / 'baseline.xml').exists())
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        receipts = self.receipts()
        self.assertEqual(6, len(receipts))
        self.assertEqual(0, receipts[4]['exit'])
        self.assertIn('null root node', receipts[4]['stderr'])
        self.assertEqual(11, receipts[5]['exit'])
        self.assertIn('no new device XML', receipts[5]['stderr'])

    def test_failed_remote_hierarchy_removal_stops_before_dump_and_retains_receipt(self):
        stale = '<hierarchy><node text="previous model" /></hierarchy>'
        (self.root / 'device.xml').write_text(stale)
        (self.device.out / 'baseline.xml').write_text(stale)
        self.mode(failure='hierarchy_remove')
        with self.assertRaisesRegex(RuntimeError, 'failed hierarchy removal'):
            self.device.snapshot('baseline')
        self.assertFalse((self.device.out / 'baseline.xml').exists())
        self.assertEqual(stale, (self.root / 'device.xml').read_text())
        receipts = self.receipts()
        self.assertEqual(4, len(receipts))
        self.assertEqual(10, receipts[-1]['exit'])
        self.assertFalse(any('uiautomator dump' in r['argv'][-1] for r in receipts))

    def test_warning_prefixed_truncated_and_crc_corrupt_pngs_are_rejected_intact(self):
        for png in (b'[Warning] Multiple displays\n' + self.PNG, self.PNG[:-3], self.PNG[:-1] + b'\x00'):
            with self.subTest(png=png):
                for f in self.device.out.iterdir(): f.unlink()
                (self.root / 'mode.json').write_text(json.dumps({'png': png.hex()}))
                with self.assertRaisesRegex(RuntimeError, 'Invalid screenshot PNG'):
                    self.device.snapshot('baseline')
                self.assertEqual(png, (self.device.out / 'baseline.png').read_bytes())
                self.assertEqual(3, len(self.receipts()))

    def test_invalid_hierarchy_still_fails_after_valid_screenshot(self):
        self.mode(xml='not xml')
        with self.assertRaises(ParseError):
            self.device.snapshot('baseline')
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        self.assertEqual('not xml\n', (self.device.out / 'baseline.xml').read_text())
        self.assertEqual(6, len(self.receipts()))

    def test_fresh_well_formed_xml_requires_a_hierarchy_root(self):
        self.mode(xml='<diagnostic />')
        with self.assertRaisesRegex(RuntimeError, 'expected hierarchy root'):
            self.device.snapshot('baseline')
        self.assertEqual('<diagnostic />\n', (self.device.out / 'baseline.xml').read_text())
        self.assertEqual(6, len(self.receipts()))

    def test_timeout_retains_partial_diagnostics_and_propagates(self):
        timeout = subprocess.TimeoutExpired('adb', 60, output=b'partial capture', stderr=b'timed out')
        with patch('android.subprocess.run', side_effect=[subprocess.CompletedProcess([], 0, b'', b''), timeout]):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.device.snapshot('baseline')
        removed, receipt = self.receipts()
        self.assertEqual('rm -f /sdcard/jarvis-screen.png', removed['argv'][4])
        self.assertIsNone(receipt['exit'])
        self.assertTrue(receipt['timed_out'])
        self.assertEqual('partial capture', receipt['stdout'])
        self.assertEqual('timed out', receipt['stderr'])
        diagnostic = (self.device.out / 'baseline-screencap.txt').read_text()
        self.assertIn('partial capture', diagnostic)
        self.assertIn('timed out', diagnostic)

    def test_capture_pull_and_hierarchy_share_existing_60_second_budget(self):
        clock, budgets = [0.0], []
        real_run = subprocess.run

        def run(*args, **kwargs):
            budgets.append(kwargs['timeout'])
            result = real_run(*args, **kwargs)
            clock[0] += 10
            return result

        with patch('android.time.monotonic', side_effect=lambda: clock[0]), patch('android.subprocess.run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, '60-second command budget'):
                self.device.snapshot('baseline')
        self.assertEqual([60, 50, 40, 30, 20, 10], budgets)
        self.assertEqual(6, len(self.receipts()))
        self.assertFalse((self.device.out / 'baseline.xml').exists())


if __name__ == "__main__":
    unittest.main()
