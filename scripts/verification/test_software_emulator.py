"""Software boot must not turn a stale boot flag or failed unlock into test coverage."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from profiles import load_profiles
from software_emulator import (SERVICES, SoftwareSession, emulator_command,
                               keyguard_dismissed, require_software_profile,
                               wait_for_android, wait_for_unlock)


PROFILE = next(profile for profile in load_profiles() if profile["id"] == "29-phone-normal")
UNLOCKED = "  isHomeRecentsComponent=false  KeyguardController:\n    mKeyguardShowing=false\n    mAodShowing=false\n    mKeyguardGoingAway=false\n"


class Clock:
    def __init__(self):
        self.seconds = 0

    def now(self):
        return self.seconds

    def pause(self, seconds):
        self.seconds += seconds


def reply(stdout, code=0):
    return subprocess.CompletedProcess([], code, stdout, "")


class SoftwareReadinessTest(unittest.TestCase):
    def test_boot_flag_with_missing_input_never_authorizes_controller(self):
        clock, calls, snapshots = Clock(), [], []

        def adb(*args, deadline):
            calls.append(args)
            if args[-1] == "sys.boot_completed":
                return reply("1\n")
            return reply(f"Service {args[-1]}: {'not found' if args[-1] == 'input' else 'found'}\n")

        with self.assertRaisesRegex(TimeoutError, '"input": false'):
            wait_for_android(adb, lambda: True, 6, now=clock.now, pause=clock.pause, record=snapshots.append)
        self.assertEqual(6, clock.now())
        self.assertTrue(snapshots[-1]["boot_completed"])
        self.assertFalse(any("keyevent" in command for command in calls))

    def test_every_service_and_boot_flag_are_required(self):
        for missing in (*SERVICES, "sys.boot_completed"):
            with self.subTest(missing=missing):
                clock = Clock()

                def adb(*args, deadline):
                    if args[-1] == "sys.boot_completed":
                        return reply("0" if missing == "sys.boot_completed" else "1")
                    return reply(f"Service {args[-1]}: {'not found' if args[-1] == missing else 'found'}")

                with self.assertRaises(TimeoutError):
                    wait_for_android(adb, lambda: True, 4, now=clock.now, pause=clock.pause)

    def test_failed_or_misidentified_binder_lookup_is_not_readiness(self):
        for invalid in (reply("Service input: found", 1), reply("Service window: found"),
                        reply("Service input: not found")):
            clock = Clock()

            def adb(*args, deadline):
                return reply("1") if args[-1] == "sys.boot_completed" else invalid

            with self.assertRaises(TimeoutError):
                wait_for_android(adb, lambda: True, 2, now=clock.now, pause=clock.pause)

    def test_delayed_services_must_be_available_together_before_deadline(self):
        clock, deadlines = Clock(), []

        def adb(*args, deadline):
            deadlines.append(deadline)
            if args[-1] == "sys.boot_completed":
                return reply("1")
            return reply(f"Service {args[-1]}: {'not found' if clock.now() < 4 else 'found'}")

        state = wait_for_android(adb, lambda: True, 6, now=clock.now, pause=clock.pause)
        self.assertEqual(4, clock.now())
        self.assertTrue(all(state["services"].values()))
        self.assertEqual({6}, set(deadlines))

    def test_slow_probe_cannot_report_success_after_budget_expires(self):
        clock = Clock()

        def adb(*args, deadline):
            clock.pause(1)
            return reply("1" if args[-1] == "sys.boot_completed" else f"Service {args[-1]}: found")

        with self.assertRaises(TimeoutError):
            wait_for_android(adb, lambda: True, 3, now=clock.now, pause=clock.pause)

    def test_emulator_death_is_immediate_failure(self):
        with self.assertRaisesRegex(RuntimeError, "Emulator exited"):
            wait_for_android(Mock(), lambda: False, 10, now=lambda: 0)

    def test_successful_input_cannot_prove_a_still_locked_keyguard(self):
        clock, raw = Clock(), []
        adb = Mock(return_value=reply(UNLOCKED.replace("mKeyguardShowing=false", "mKeyguardShowing=true")))
        with self.assertRaisesRegex(TimeoutError, "keyguard dismissal"):
            wait_for_unlock(adb, lambda: True, 6, now=clock.now, pause=clock.pause, record=raw.append)
        self.assertEqual(6, clock.now())
        self.assertIn("mKeyguardShowing=true", raw[-1])

    def test_unlock_requires_actual_unique_scoped_flags(self):
        self.assertTrue(keyguard_dismissed(UNLOCKED))
        self.assertTrue(keyguard_dismissed(UNLOCKED.replace("isHomeRecentsComponent=false  ", "")))
        for invalid in (UNLOCKED.replace("mAodShowing=false", "mAodShowing=true"),
                        UNLOCKED.replace("mKeyguardGoingAway=false", "mKeyguardGoingAway=true"),
                        UNLOCKED.replace("KeyguardController:", "OtherController:"),
                        UNLOCKED + UNLOCKED,
                        "  KeyguardController:\n  OtherController:\n    mKeyguardShowing=false\n    mAodShowing=false\n    mKeyguardGoingAway=false\n",
                        UNLOCKED.replace("    mAodShowing=false\n", "")):
            with self.subTest(raw=invalid):
                self.assertFalse(keyguard_dismissed(invalid))

    def test_delayed_unlock_must_finish_within_original_deadline(self):
        clock = Clock()

        def adb(*args, deadline, **kwargs):
            self.assertEqual(6, deadline)
            return reply(UNLOCKED if clock.now() >= 4 else UNLOCKED.replace("mKeyguardShowing=false", "mKeyguardShowing=true"))

        wait_for_unlock(adb, lambda: True, 6, now=clock.now, pause=clock.pause)
        self.assertEqual(4, clock.now())


class SoftwareSessionTest(unittest.TestCase):
    def test_native_host_and_exact_api29_guest_are_enforced(self):
        require_software_profile(PROFILE, "Darwin", "arm64")
        for host in (("Linux", "x86_64"), ("Darwin", "x86_64")):
            with self.assertRaisesRegex(RuntimeError, "native Darwin/arm64"):
                require_software_profile(PROFILE, *host)
        for field, value in (("api", 30), ("arch", "x86_64"), ("acceleration", "kvm"),
                             ("target", "google_apis"), ("target", "google_apis_ps16k")):
            with self.assertRaisesRegex(RuntimeError, "genuine API 29"):
                require_software_profile(dict(PROFILE, **{field: value}), "Darwin", "arm64")

    def test_sdk_install_and_avd_creation_use_the_same_exact_aosp_api29_image(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
            calls = []

            def run(command, **kwargs):
                calls.append(command)
                if "create" in command:
                    config = session.avd_home / "jarvis-api29-software.avd/config.ini"
                    config.parent.mkdir()
                    config.write_text("hw.cpu.ncore=1\nhw.ramSize=1536M\nvm.heapSize=256M\n"
                                      "hw.lcd.width=1080\nhw.lcd.height=1920\nhw.lcd.density=420\n")
                return reply("")

            with patch.object(session, "run", side_effect=run):
                session.provision()
            image = "system-images;android-29;default;arm64-v8a"
            installed = next(command for command in calls if image in command and "--install" in command)
            created = next(command for command in calls if "create" in command)
            self.assertIn("--channel=0", installed)
            self.assertEqual(image, created[created.index("--package") + 1])
            self.assertFalse(any("google_apis" in argument for command in calls for argument in command))
            config = (session.diagnostics / "avd-config.ini").read_text()
            effective = dict(line.split("=", 1) for line in config.splitlines() if "=" in line)
            self.assertEqual("2", effective["hw.cpu.ncore"])
            self.assertEqual("2048M", effective["hw.ramSize"])
            self.assertEqual("256M", effective["vm.heapSize"])
            self.assertNotIn("hw.heapSize", effective)
            self.assertEqual(len(effective), len([line for line in config.splitlines() if "=" in line]),
                             "Generated and overridden configuration keys must be unique")
            density = int(effective["hw.lcd.density"])
            for dimension, original in (("width", 1080), ("height", 1920)):
                pixels = int(effective[f"hw.lcd.{dimension}"])
                self.assertEqual(original * density, pixels * 420, "Pixel 2 dp viewport must remain exact")
                self.assertEqual(original, pixels * 2, "Software raster dimensions must halve")
            self.assertEqual({"boot_timeout": 900, "job_timeout": 60},
                             {key: session.profile[key] for key in ("boot_timeout", "job_timeout")})
            session.close()

    def test_physical_framebuffer_receipt_rejects_skin_or_wm_override(self):
        for output in ("Physical size: 1080x1920", "Physical size: 1080x1920\nOverride size: 540x960",
                       "Physical size: 540x960\nOverride size: 1080x1920"):
            with self.subTest(output=output), tempfile.TemporaryDirectory() as temporary:
                session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
                with patch.object(session, "adb", return_value=reply(output)) as adb:
                    with self.assertRaisesRegex(RuntimeError, "physical size must be 540x960"):
                        session.require_display(900)
                adb.assert_called_once_with("shell", "wm", "size", deadline=900, check=True)
                self.assertEqual(output, json.loads((session.diagnostics / "display-state.json").read_text())["size"]["stdout"])
                self.assertNotIn("display", session.report)

    def test_density_receipt_rejects_mismatched_or_overridden_dpi(self):
        for output in ("Physical density: 420", "Physical density: 420\nOverride density: 210",
                       "Physical density: 210\nOverride density: 420"):
            with self.subTest(output=output), tempfile.TemporaryDirectory() as temporary:
                session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
                with patch.object(session, "adb", side_effect=[reply("Physical size: 540x960"), reply(output)]):
                    with self.assertRaisesRegex(RuntimeError, "physical density must be 210"):
                        session.require_display(900)
                self.assertEqual(output, json.loads((session.diagnostics / "display-state.json").read_text())["density"]["stdout"])
                self.assertNotIn("display", session.report)

    def test_late_physical_display_probes_cannot_mark_configuration_verified(self):
        clock = Clock()
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=clock.now)

            def adb(*args, deadline, **kwargs):
                self.assertEqual(3, deadline)
                clock.pause(2)
                return reply("Physical size: 540x960" if args[-1] == "size" else "Physical density: 210")

            with patch.object(session, "adb", side_effect=adb):
                with self.assertRaisesRegex(TimeoutError, "deadline expired"):
                    session.require_display(3)
            self.assertNotIn("display", session.report)
            self.assertTrue((session.diagnostics / "display-state.json").exists())

    def test_host_receipts_use_only_bounded_read_only_commands(self):
        clock, calls = Clock(), []
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=clock.now)
            session.emulator = Mock(pid=12345)

            def run(command, **kwargs):
                self.assertEqual(2, kwargs["timeout"])
                self.assertNotIn("shell", kwargs)
                calls.append(command)
                clock.pause(1)
                return reply("raw host resource state")

            with patch("software_emulator.subprocess.run", side_effect=run):
                session.capture_host_resources("boot-failed", 10)
            self.assertEqual([["/usr/sbin/sysctl", "-n", "hw.ncpu", "hw.memsize"],
                              ["/usr/bin/vm_stat"], ["/usr/bin/memory_pressure", "-Q"],
                              ["/bin/ps", "-p", "12345", "-o", "pid=,pcpu=,rss=,comm="]], calls)
            receipts = [json.loads(line) for line in (session.diagnostics / "host-resources.jsonl").read_text().splitlines()]
            self.assertEqual(4, len(receipts))
            self.assertTrue(all(receipt["stage"] == "boot-failed" and receipt["exit_code"] == 0
                                and receipt["stdout"] == "raw host resource state" for receipt in receipts))

    def test_host_receipt_timeouts_share_original_deadline_and_keep_partial_output(self):
        clock, limits = Clock(), []
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=clock.now)
            session.emulator = Mock(pid=12345)

            def run(command, **kwargs):
                limits.append(kwargs["timeout"])
                clock.pause(kwargs["timeout"])
                raise subprocess.TimeoutExpired(command, kwargs["timeout"], output=b"partial host state")

            with patch("software_emulator.subprocess.run", side_effect=run):
                session.capture_host_resources("boot-failed", 3)
                session.capture_host_resources("boot-failed", 3)
            self.assertEqual([2, 1], limits)
            self.assertEqual(3, clock.now(), "Diagnostics must not renew or overrun the original budget")
            receipts = [json.loads(line) for line in (session.diagnostics / "host-resources.jsonl").read_text().splitlines()]
            self.assertEqual([124, 124], [receipt["exit_code"] for receipt in receipts[:2]])
            self.assertTrue(all(receipt["stdout"] == "partial host state" for receipt in receipts[:2]))
            self.assertIn("deadline expired", receipts[-1]["error"])

    def test_optional_receipt_write_failure_cannot_replace_boot_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=lambda: 0)
            emulator = Mock(pid=12345)
            original_open = Path.open

            def open_file(path, *args, **kwargs):
                if path.name == "host-resources.jsonl":
                    raise OSError("Host receipt storage unavailable")
                return original_open(path, *args, **kwargs)

            with patch("software_emulator.subprocess.Popen", return_value=emulator), \
                    patch.object(session, "run", return_value=reply("raw resource state")), \
                    patch.object(session, "wait_ready", side_effect=TimeoutError("Input service never became ready")), \
                    patch.object(Path, "open", open_file):
                with self.assertRaisesRegex(TimeoutError, "Input service never became ready"):
                    session.boot()
            self.assertFalse(session.report["passed"])
            self.assertNotEqual("ready", session.report["status"])
            self.assertEqual(["Host receipt storage unavailable"] * 2, session.report["host_resource_errors"])

    def test_unlock_failure_retains_startup_evidence_and_never_reaches_ready(self):
        clock = Clock()
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary) / "evidence"
            session = SoftwareSession(PROFILE, out, "/sdk", now=clock.now)
            emulator = Mock(pid=12345)
            emulator.poll.return_value = None
            emulator.returncode = -15

            def ready(deadline):
                self.assertEqual(900, deadline)
                clock.pause(850)

            def adb(*args, deadline, **kwargs):
                self.assertEqual(900, deadline)
                self.assertTrue(kwargs["check"])
                raise RuntimeError("No service published for: input")

            with patch("software_emulator.subprocess.Popen", return_value=emulator), \
                    patch.object(session, "wait_ready", side_effect=ready), \
                    patch.object(session, "run", side_effect=OSError("Host resource query unavailable")), \
                    patch.object(session, "adb", side_effect=adb):
                with self.assertRaisesRegex(RuntimeError, "No service published"):
                    session.boot()
            self.assertFalse(out.exists(), "Controller requires its own fresh output directory")
            session.report["errors"].append("No service published for: input")
            session.report["status"] = "failed"
            with patch.object(session, "adb", return_value=reply("raw binder diagnostics")), \
                    patch("software_emulator.os.killpg") as kill:
                session.close()
            kill.assert_called_once()
            report = json.loads((out / "software-emulator/startup.json").read_text())
            self.assertFalse(report["passed"])
            self.assertEqual("failed", report["status"])
            self.assertIn("No service published", report["errors"][0])
            self.assertTrue((out / "software-emulator/emulator-stdout.txt").exists())
            receipts = [json.loads(line) for line in (out / "software-emulator/host-resources.jsonl").read_text().splitlines()]
            self.assertEqual({"boot-start", "boot-failed"}, {receipt["stage"] for receipt in receipts})
            self.assertTrue(all("Host resource query unavailable" in receipt["error"] for receipt in receipts))

    def test_successful_unlock_settings_and_final_readiness_share_one_budget(self):
        clock, deadlines = Clock(), []
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=clock.now)
            emulator = Mock(pid=12345)
            emulator.poll.return_value = None

            def ready(deadline):
                deadlines.append(deadline)
                clock.pause(800 if clock.now() < 800 else 1)

            def resources(stage, deadline):
                deadlines.append(deadline)
                clock.pause(1)

            def adb(*args, deadline, **kwargs):
                deadlines.append(deadline)
                if "input" in args:
                    self.assertEqual(99, kwargs["timeout"], "Resource receipts consume the existing boot budget")
                if "settings" in args:
                    self.assertEqual(30, kwargs["timeout"], "Animation setting deadlines remain unchanged")
                if "dumpsys" not in args:
                    self.assertTrue(kwargs["check"])
                clock.pause(1)
                if "wm" in args:
                    return reply("Physical size: 540x960" if args[-1] == "size" else "Physical density: 210")
                return reply(UNLOCKED if "dumpsys" in args else "")

            with patch("software_emulator.subprocess.Popen", return_value=emulator), \
                    patch.object(session, "wait_ready", side_effect=ready), \
                    patch.object(session, "capture_host_resources", side_effect=resources), \
                    patch.object(session, "adb", side_effect=adb):
                session.boot()
            self.assertEqual("ready", session.report["status"])
            self.assertEqual([900] * 11, deadlines)
            self.assertFalse(session.report["passed"], "Ready emulator alone does not pass the controller")
            self.assertEqual({"width": 540, "height": 960, "density_dpi": 210}, session.report["display"])

    def test_process_disappearance_during_close_still_attaches_all_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
            session.emulator = Mock(pid=12345, returncode=1)
            session.emulator.poll.return_value = None
            (session.diagnostics / "guest-startup-logcat.txt").write_text("raw Android startup failure")
            (session.avd_home / "userdata.img").write_text("disposable data")
            avd_home = session.avd_home
            with patch.object(session, "adb", return_value=reply("raw final diagnostics")), \
                    patch("software_emulator.os.killpg", side_effect=ProcessLookupError):
                session.close()
            self.assertFalse(avd_home.exists())
            evidence = session.out / "software-emulator"
            self.assertEqual("raw Android startup failure", (evidence / "guest-startup-logcat.txt").read_text())
            self.assertTrue((evidence / "startup.json").exists())
            self.assertFalse(list(evidence.rglob("userdata.img")))

    def test_stubborn_emulator_gets_bounded_sigkill_and_keeps_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
            session.emulator = Mock(pid=12345, returncode=-9)
            session.emulator.poll.return_value = None
            session.emulator.wait.side_effect = [subprocess.TimeoutExpired("emulator", 10), -9]
            with patch.object(session, "adb", return_value=reply("raw final diagnostics")), \
                    patch("software_emulator.os.killpg") as kill:
                session.close()
            self.assertEqual(2, kill.call_count)
            self.assertEqual(9, kill.call_args.args[1])
            self.assertTrue((session.out / "software-emulator/startup.json").exists())

    def test_cleanup_failure_retains_logs_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
            session.emulator = Mock(pid=12345, returncode=None)
            session.emulator.poll.return_value = None
            session.emulator.wait.side_effect = subprocess.TimeoutExpired("emulator", 10)
            with patch.object(session, "adb", return_value=reply("raw final diagnostics")), \
                    patch("software_emulator.os.killpg"):
                with self.assertRaisesRegex(RuntimeError, "diagnostics retained"):
                    session.close()
            report = json.loads((session.out / "software-emulator/startup.json").read_text())
            self.assertFalse(report["passed"])
            self.assertEqual("failed", report["status"])

    def test_subprocess_timeout_keeps_partial_raw_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=lambda: 0)
            timeout = subprocess.TimeoutExpired(["probe"], 3, output=b"raw startup", stderr=b"stalled binder")
            with patch("software_emulator.subprocess.run", side_effect=timeout) as run:
                result = session.run(["probe"], deadline=3, timeout=60, check=False)
            self.assertEqual(3, run.call_args.kwargs["timeout"])
            self.assertEqual(124, result.returncode)
            evidence = json.loads((session.diagnostics / "commands.jsonl").read_text())
            self.assertEqual("raw startup", evidence["stdout"])
            self.assertIn("stalled binder", evidence["stderr"])

    def test_launcher_disables_both_acceleration_paths_and_logs_from_startup(self):
        command = emulator_command(Path("/sdk"), Path("/evidence"))
        self.assertEqual("off", command[command.index("-accel") + 1])
        self.assertEqual("-HVF", command[command.index("-feature") + 1])
        self.assertEqual("*:V", command[command.index("-logcat") + 1])
        self.assertEqual("/evidence/guest-startup-logcat.txt", command[command.index("-logcat-output") + 1])
        self.assertEqual("swiftshader_indirect", command[command.index("-gpu") + 1])
        self.assertNotIn("-no-watchdog", command)

    def test_launcher_timezone_does_not_inherit_invalid_host_detection(self):
        with patch.dict("os.environ", {"TZ": "Unknown/Unknown"}):
            command = emulator_command(Path("/sdk"), Path("/evidence"))
        self.assertEqual(1, command.count("-timezone"))
        self.assertEqual("Etc/UTC", command[command.index("-timezone") + 1])
        self.assertNotIn("Unknown/Unknown", command)


if __name__ == "__main__":
    unittest.main()
