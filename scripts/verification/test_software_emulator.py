"""Software boot must not turn a stale boot flag or failed unlock into test coverage."""
import json
import hashlib
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from xml.dom import minidom
import zipfile

from profiles import load_profiles
from software_emulator import (EMULATOR_PIN, SERVICES, SoftwareSession, emulator_command, main,
                               keyguard_dismissed, read_native_boot_log, require_software_profile,
                               wait_for_android, wait_for_boot_broadcast, wait_for_unlock)


PROFILE = next(profile for profile in load_profiles() if profile["id"] == "29-phone-normal")
UNLOCKED = "  isHomeRecentsComponent=false  KeyguardController:\n    mKeyguardShowing=false\n    mAodShowing=false\n    mKeyguardGoingAway=false\n"
BOOT_DELIVERED = "10-04 04:03:49.805   279   353 I ActivityManager: Finished processing BOOT_COMPLETED for u0\n"


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


class BootBroadcastTest(unittest.TestCase):
    def test_pending_delivery_must_complete_for_current_server_before_original_deadline(self):
        clock, deadlines, receipts = Clock(), [], []

        def adb(*args, deadline, timeout):
            deadlines.append(deadline)
            self.assertEqual(15, timeout)
            if args[0] == "shell":
                self.assertEqual(("shell", "pidof", "system_server"), args)
                return reply("279\n")
            self.assertEqual(("logcat", "-b", "system", "-d", "-v", "threadtime",
                              "-s", "ActivityManager:I"), args)
            return reply(BOOT_DELIVERED if clock.now() >= 4 else "Posting BOOT_COMPLETED user #0\n")

        result = wait_for_boot_broadcast(adb, lambda: True, 6, now=clock.now, pause=clock.pause,
                                        record=lambda raw: receipts.append(json.loads(json.dumps(raw))))
        self.assertEqual(4, clock.now())
        self.assertEqual({6}, set(deadlines))
        self.assertEqual(279, result["system_server_pid"])
        self.assertTrue(result["completed"])
        self.assertTrue(any(not receipt["completed"] for receipt in receipts))
        self.assertEqual(BOOT_DELIVERED, receipts[-1]["probes"]["logcat"]["stdout"])

    def test_wrong_server_user_tag_partial_and_failed_probes_never_complete(self):
        for raw, log_exit, pid in ((BOOT_DELIVERED.replace("279", "278"), 0, "279"),
                                  (BOOT_DELIVERED.replace("for u0", "for u10"), 0, "279"),
                                  (BOOT_DELIVERED.replace("ActivityManager", "OtherService"), 0, "279"),
                                  (BOOT_DELIVERED.replace("for u0", "for u"), 0, "279"),
                                  (BOOT_DELIVERED.replace("for u0", "for u0 pending"), 0, "279"),
                                  (BOOT_DELIVERED, 1, "279"), (BOOT_DELIVERED, 0, "279 280")):
            with self.subTest(raw=raw, log_exit=log_exit, pid=pid):
                clock = Clock()

                def adb(*args, **kwargs):
                    return reply(pid) if args[0] == "shell" else reply(raw, log_exit)

                with self.assertRaisesRegex(TimeoutError, "Actual user0 BOOT_COMPLETED delivery"):
                    wait_for_boot_broadcast(adb, lambda: True, 4, now=clock.now, pause=clock.pause)
                self.assertEqual(4, clock.now())

    def test_system_server_restart_during_receipt_rejects_old_completion(self):
        clock = Clock()
        adb = Mock(side_effect=[reply("279"), reply(BOOT_DELIVERED), reply("775")])
        with self.assertRaises(TimeoutError):
            wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause)
        self.assertEqual(3, adb.call_count)

    def test_failed_pid_query_does_not_authorize_matching_marker(self):
        clock = Clock()
        adb = Mock(side_effect=[reply("279", 1), reply(BOOT_DELIVERED), reply("279")])
        with self.assertRaises(TimeoutError):
            wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause)

    def test_current_launch_completion_can_precede_wait_start(self):
        adb = Mock(side_effect=[reply("279"), reply(BOOT_DELIVERED), reply("279")])
        result = wait_for_boot_broadcast(adb, lambda: True, 2, now=lambda: 0)
        self.assertTrue(result["completed"])

    def test_late_receipt_cannot_renew_deadline_or_authorize_controller(self):
        clock, receipts = Clock(), []

        def adb(*args, deadline, **kwargs):
            self.assertEqual(3, deadline)
            clock.pause(1)
            return reply("279" if args[0] == "shell" else BOOT_DELIVERED)

        with self.assertRaisesRegex(TimeoutError, '"completed": true'):
            wait_for_boot_broadcast(adb, lambda: True, 3, now=clock.now, pause=clock.pause,
                                    record=lambda raw: receipts.append(json.loads(json.dumps(raw))))
        self.assertEqual(3, clock.now())
        self.assertEqual(BOOT_DELIVERED, receipts[-1]["probes"]["logcat"]["stdout"])

    def test_budget_expiring_mid_poll_keeps_partial_receipt_without_more_queries(self):
        clock, receipts, calls = Clock(), [], []

        def adb(*args, **kwargs):
            calls.append(args)
            clock.pause(2)
            return reply("279" if args[0] == "shell" else BOOT_DELIVERED)

        with self.assertRaisesRegex(TimeoutError, "declared boot timeout"):
            wait_for_boot_broadcast(adb, lambda: True, 3, now=clock.now, pause=clock.pause,
                                    record=lambda raw: receipts.append(json.loads(json.dumps(raw))))
        self.assertEqual(2, len(calls))
        self.assertEqual({"pid_before", "logcat"}, set(receipts[-1]["probes"]))

    def test_emulator_death_cannot_authorize_a_valid_receipt(self):
        running = Mock(side_effect=[True, False, False])
        adb = Mock(side_effect=[reply("279"), reply(BOOT_DELIVERED), reply("279")])
        clock = Clock()
        with self.assertRaisesRegex(RuntimeError, "Emulator exited"):
            wait_for_boot_broadcast(adb, running, 4, now=clock.now, pause=clock.pause)


class NativeBootBroadcastTest(unittest.TestCase):
    def test_bounded_tail_keeps_complete_marker_and_excludes_partial_lines(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            prefix = b"earlier guest output\n" * 20000
            suffix = b"an unfinished guest line"
            path.write_bytes(prefix + BOOT_DELIVERED.encode() + suffix)
            receipt = read_native_boot_log(path, deadline=2, now=lambda: 0)
            self.assertEqual(0, receipt["exit_code"])
            self.assertLessEqual(receipt["read_bytes"], 256 * 1024)
            self.assertGreater(receipt["start_offset"], 0)
            self.assertIn(BOOT_DELIVERED, receipt["stdout"])
            self.assertNotIn(suffix.decode(), receipt["stdout"])
            self.assertEqual(len(suffix), receipt["incomplete_suffix_bytes"])
            self.assertEqual(path.stat().st_size, receipt["file_size_bytes"])
            self.assertEqual("native-startup-logcat", receipt["source"])
            self.assertEqual(2, receipt["deadline_monotonic_seconds"])

    def test_fragmented_marker_is_never_reconstructed_from_tail_or_unfinished_line(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            for raw, limit in ((BOOT_DELIVERED.encode(), len(BOOT_DELIVERED) - 10),
                               (BOOT_DELIVERED.rstrip("\n").encode(), 256 * 1024)):
                with self.subTest(raw=raw, limit=limit):
                    path.write_bytes(raw)
                    clock = Clock()
                    adb = Mock(return_value=reply("279"))
                    with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
                        wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause,
                                                log_reader=lambda deadline: read_native_boot_log(
                                                    path, deadline=deadline, now=clock.now, max_bytes=limit))
                    adb.assert_not_called()

    def test_no_native_marker_never_queries_guest_and_keeps_original_boot_deadline(self):
        clock, receipts = Clock(), []
        adb = Mock()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            path.write_text("Posting BOOT_COMPLETED user #0\n")

            def read(deadline):
                self.assertEqual(900, deadline)
                return read_native_boot_log(path, deadline=deadline, now=clock.now)

            with self.assertRaisesRegex(TimeoutError, '"completed": false'):
                wait_for_boot_broadcast(adb, lambda: True, 900, now=clock.now, pause=clock.pause,
                                        log_reader=read, record=receipts.append)
        adb.assert_not_called()
        self.assertEqual(900, clock.now())
        self.assertTrue(receipts)
        self.assertTrue(all(set(receipt["probes"]) == {"log_candidate"} for receipt in receipts))
        self.assertTrue(all(not receipt["completed"] for receipt in receipts))

    def test_native_completion_is_read_between_fresh_pid_checks_without_guest_logcat(self):
        clock, operations, receipts = Clock(), [], []
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            path.write_text("Posting BOOT_COMPLETED user #0\n")

            def adb(*args, deadline, timeout):
                self.assertEqual(("shell", "pidof", "system_server"), args)
                self.assertEqual((6, 15), (deadline, timeout))
                operations.append("pid")
                return reply("279\n")

            def read(deadline):
                operations.append("native")
                if clock.now() >= 2:
                    path.write_text(BOOT_DELIVERED)
                return read_native_boot_log(path, deadline=deadline, now=clock.now)

            result = wait_for_boot_broadcast(adb, lambda: True, 6, now=clock.now, pause=clock.pause,
                                            log_reader=read,
                                            record=lambda state: receipts.append(json.loads(json.dumps(state))))
            self.assertEqual(["native", "native", "pid", "native", "pid"], operations)
            self.assertEqual(2, clock.now())
            self.assertTrue(result["completed"])
            self.assertEqual(279, result["system_server_pid"])
            self.assertFalse(receipts[3]["completed"])
            self.assertEqual(BOOT_DELIVERED, receipts[-1]["probes"]["logcat"]["stdout"])

    def test_candidate_cannot_replace_fresh_log_read_between_pid_probes(self):
        for fresh in (b"", BOOT_DELIVERED.replace("279", "775").encode(),
                      BOOT_DELIVERED.replace("for u0", "for u10").encode(),
                      BOOT_DELIVERED.replace("ActivityManager", "OtherService").encode(),
                      BOOT_DELIVERED.replace("ActivityManager: ", "ActivityManager:\n").encode(),
                      BOOT_DELIVERED.replace("279   353", "279\n353").encode(),
                      BOOT_DELIVERED.rstrip("\n").encode(), b"\xff\n" + BOOT_DELIVERED.encode(), None):
            with self.subTest(fresh=fresh), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "guest-startup-logcat.txt"
                path.write_text(BOOT_DELIVERED)
                clock, receipts, operations = Clock(), [], []

                def adb(*args, **kwargs):
                    self.assertEqual(("shell", "pidof", "system_server"), args)
                    operations.append("pid")
                    if operations == ["native", "pid"]:
                        if fresh is None:
                            path.unlink()
                        else:
                            path.write_bytes(fresh)
                    return reply("279")

                def read(deadline):
                    operations.append("native")
                    return read_native_boot_log(path, deadline=deadline, now=clock.now)

                with self.assertRaisesRegex(TimeoutError, '"completed": false'):
                    wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause,
                                            log_reader=read, record=receipts.append)
                self.assertEqual(["native", "pid", "native", "pid"], operations)
                self.assertEqual(BOOT_DELIVERED, receipts[-1]["probes"]["log_candidate"]["stdout"])
                self.assertFalse(receipts[-1]["completed"])

    def test_native_marker_requires_correct_current_server_and_successful_stable_pid_probes(self):
        cases = [(BOOT_DELIVERED, reply("279 280"), reply("279")),
                 (BOOT_DELIVERED, reply("279", 1), reply("279")),
                 (BOOT_DELIVERED, reply("279"), reply("279", 1)),
                 (BOOT_DELIVERED, reply("279"), reply("775"))]
        cases += [(BOOT_DELIVERED.replace(old, new), reply("279"), reply("279"))
                  for old, new in (("279", "278"), ("for u0", "for u10"),
                                   ("I ActivityManager", "W ActivityManager"),
                                   ("ActivityManager", "OtherService"),
                                   ("for u0", "for u0 pending"))]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            for raw, before, after in cases:
                with self.subTest(raw=raw, before=before, after=after):
                    path.write_text(raw)
                    clock = Clock()
                    adb = Mock(side_effect=[before, after])
                    with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
                        wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause,
                                                log_reader=lambda deadline: read_native_boot_log(
                                                    path, deadline=deadline, now=clock.now))

    def test_wrong_user_tag_priority_or_incomplete_message_never_queries_guest(self):
        for old, new in (("for u0", "for u10"), ("I ActivityManager", "W ActivityManager"),
                         ("ActivityManager", "OtherService"), ("for u0", "for u"),
                         ("for u0", "for u0 pending"), ("279", "0"),
                         ("ActivityManager: ", "ActivityManager:\n"), ("279   353", "279\n353")):
            with self.subTest(old=old, new=new), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "guest-startup-logcat.txt"
                path.write_text(BOOT_DELIVERED.replace(old, new))
                clock, adb = Clock(), Mock()
                with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
                    wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause,
                                            log_reader=lambda deadline: read_native_boot_log(
                                                path, deadline=deadline, now=clock.now))
                adb.assert_not_called()

    def test_failed_candidate_read_with_matching_marker_never_queries_guest(self):
        for code in (1, 124):
            with self.subTest(code=code):
                clock, adb = Clock(), Mock()
                read = Mock(return_value={"exit_code": code, "stdout": BOOT_DELIVERED, "stderr": "read failed"})
                with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
                    wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause,
                                            log_reader=read)
                adb.assert_not_called()
                self.assertEqual(2, clock.now())

    def test_missing_unreadable_or_malformed_native_log_cannot_fall_back_to_adb_logcat(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            for mode in ("missing", "directory", "invalid-utf8"):
                with self.subTest(mode=mode):
                    if mode == "directory":
                        path.mkdir()
                    elif mode == "invalid-utf8":
                        path.write_bytes(b"\xff\n" + BOOT_DELIVERED.encode())
                    clock, receipts = Clock(), []
                    adb = Mock(return_value=reply("279"))
                    with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
                        wait_for_boot_broadcast(adb, lambda: True, 2, now=clock.now, pause=clock.pause,
                                                record=receipts.append,
                                                log_reader=lambda deadline: read_native_boot_log(
                                                    path, deadline=deadline, now=clock.now))
                    adb.assert_not_called()
                    self.assertNotEqual(0, receipts[-1]["probes"]["log_candidate"]["exit_code"])
                    self.assertTrue(receipts[-1]["probes"]["log_candidate"]["stderr"])
                    if path.is_dir():
                        path.rmdir()
                    elif path.exists():
                        path.unlink()

    def test_file_truncated_during_live_observation_is_not_completion_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            path.write_text(BOOT_DELIVERED)
            original_open = Path.open

            class TruncatingReader:
                def __enter__(self):
                    self.stream = original_open(path, "r+b")
                    return self

                def __exit__(self, *args):
                    self.stream.close()

                def fileno(self):
                    return self.stream.fileno()

                def seek(self, offset):
                    return self.stream.seek(offset)

                def read(self, size):
                    raw = self.stream.read(size)
                    self.stream.truncate(0)
                    return raw

            with patch("software_emulator.Path.open", return_value=TruncatingReader()):
                receipt = read_native_boot_log(path, deadline=2, now=lambda: 0)
            self.assertNotEqual(0, receipt["exit_code"])
            self.assertIn("truncated", receipt["stderr"])
            self.assertEqual("", receipt["stdout"])

    def test_late_candidate_read_cannot_query_guest_or_renew_original_deadline(self):
        clock, receipts = Clock(), []
        adb = Mock(return_value=reply("279"))

        def read(deadline):
            self.assertEqual(3, deadline)
            clock.pause(3)
            return {"source": "native-startup-logcat", "exit_code": 0,
                    "stdout": BOOT_DELIVERED, "stderr": ""}

        with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
            wait_for_boot_broadcast(adb, lambda: True, 3, now=clock.now, pause=clock.pause,
                                    log_reader=read, record=receipts.append)
        adb.assert_not_called()
        self.assertEqual({"log_candidate"}, set(receipts[-1]["probes"]))

    def test_late_native_validation_stops_queries_without_renewing_deadline(self):
        for late_probe in ("pid_before", "logcat"):
            with self.subTest(late_probe=late_probe):
                clock, receipts = Clock(), []

                def adb(*args, deadline, **kwargs):
                    self.assertEqual(3, deadline)
                    if late_probe == "pid_before":
                        clock.pause(3)
                    return reply("279")

                def read(deadline):
                    self.assertEqual(3, deadline)
                    if late_probe == "logcat" and receipts:
                        clock.pause(3)
                    return {"exit_code": 0, "stdout": BOOT_DELIVERED, "stderr": ""}

                query = Mock(side_effect=adb)
                with self.assertRaisesRegex(TimeoutError, "BOOT_COMPLETED delivery"):
                    wait_for_boot_broadcast(query, lambda: True, 3, now=clock.now, pause=clock.pause,
                                            log_reader=read, record=receipts.append)
                self.assertEqual(1, query.call_count)
                self.assertEqual(3, clock.now())
                expected = {"log_candidate", "pid_before"}
                if late_probe == "logcat":
                    expected.add("logcat")
                self.assertEqual(expected, set(receipts[-1]["probes"]))

    def test_native_reader_retains_late_marker_as_failure_evidence_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            path.write_text(BOOT_DELIVERED)
            receipt = read_native_boot_log(path, deadline=3, now=Mock(side_effect=[0, 3, 3]))
            self.assertEqual(124, receipt["exit_code"])
            self.assertEqual(BOOT_DELIVERED, receipt["stdout"])
            self.assertEqual(3, receipt["observed_monotonic_seconds"])
            self.assertIn("deadline expired", receipt["stderr"])

    def test_late_after_pid_and_emulator_death_reject_an_otherwise_valid_native_marker(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "guest-startup-logcat.txt"
            path.write_text(BOOT_DELIVERED)
            for late in (True, False):
                with self.subTest(late=late):
                    clock = Clock()
                    running = (lambda: True) if late else Mock(side_effect=[True, False, False])

                    def adb(*args, **kwargs):
                        clock.pause(1.5 if late else 0)
                        return reply("279")

                    with self.assertRaises(TimeoutError if late else RuntimeError):
                        wait_for_boot_broadcast(adb, running, 3, now=clock.now, pause=clock.pause,
                                                log_reader=lambda deadline: read_native_boot_log(
                                                    path, deadline=deadline, now=clock.now))


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

            with patch.object(session, "run", side_effect=run), \
                    patch.object(session, "pin_emulator") as pin, \
                    patch("software_emulator.platform.system", return_value="Darwin"), \
                    patch("software_emulator.platform.machine", return_value="arm64"):
                session.provision()
            pin.assert_called_once()
            image = "system-images;android-29;default;arm64-v8a"
            installed = next(command for command in calls if image in command and "--install" in command)
            created = next(command for command in calls if "create" in command)
            self.assertIn("--channel=0", installed)
            self.assertEqual(image, created[created.index("--package") + 1])
            self.assertFalse(any("google_apis" in argument for command in calls for argument in command))
            config = (session.diagnostics / "avd-config.ini").read_text()
            effective = dict(line.split("=", 1) for line in config.splitlines() if "=" in line)
            self.assertEqual("1", effective["hw.cpu.ncore"])
            command = emulator_command(Path("/sdk"), session.diagnostics)
            self.assertEqual(effective["hw.cpu.ncore"], command[command.index("-smp") + 1])
            self.assertEqual("2048M", effective["hw.ramSize"])
            self.assertEqual("256M", effective["vm.heapSize"])
            self.assertNotIn("hw.heapSize", effective)
            self.assertEqual(len(effective), len([line for line in config.splitlines() if "=" in line]),
                             "Generated and overridden configuration keys must be unique")
            density = int(effective["hw.lcd.density"])
            for dimension, original in (("width", 1080), ("height", 1920)):
                pixels = int(effective[f"hw.lcd.{dimension}"])
                self.assertEqual(original * density, pixels * 420, "Pixel 2 dp viewport must remain exact")
                self.assertEqual(original, pixels * 3, "Supported software raster dimensions must be one-third")
            self.assertEqual({"boot_timeout": 900, "job_timeout": 60},
                             {key: session.profile[key] for key in ("boot_timeout", "job_timeout")})
            session.close()

    def test_physical_framebuffer_receipt_rejects_skin_or_wm_override(self):
        for output in ("Physical size: 540x960", "Physical size: 720x1280", "Physical size: 1080x1920",
                       "Physical size: 1080x1920\nOverride size: 360x640",
                       "Physical size: 360x640\nOverride size: 1080x1920"):
            with self.subTest(output=output), tempfile.TemporaryDirectory() as temporary:
                session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
                with patch.object(session, "adb", return_value=reply(output)) as adb:
                    with self.assertRaisesRegex(RuntimeError, "physical size must be 360x640"):
                        session.require_display(900)
                adb.assert_called_once_with("shell", "wm", "size", deadline=900, check=True)
                self.assertEqual(output, json.loads((session.diagnostics / "display-state.json").read_text())["size"]["stdout"])
                self.assertNotIn("display", session.report)

    def test_density_receipt_rejects_mismatched_or_overridden_dpi(self):
        for output in ("Physical density: 210", "Physical density: 280", "Physical density: 420",
                       "Physical density: 420\nOverride density: 140",
                       "Physical density: 140\nOverride density: 420"):
            with self.subTest(output=output), tempfile.TemporaryDirectory() as temporary:
                session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk")
                with patch.object(session, "adb", side_effect=[reply("Physical size: 360x640"), reply(output)]):
                    with self.assertRaisesRegex(RuntimeError, "physical density must be 140"):
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
                return reply("Physical size: 360x640" if args[-1] == "size" else "Physical density: 140")

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

    def test_graphics_receipt_failure_cannot_replace_boot_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=lambda: 0)
            original_open = Path.open

            def open_file(path, *args, **kwargs):
                if path.name == "emulator-stdout.txt" and args == ("rb",):
                    raise OSError("Renderer receipt unavailable")
                return original_open(path, *args, **kwargs)

            with patch("software_emulator.subprocess.Popen", return_value=Mock(pid=12345)), \
                    patch.object(session, "capture_host_resources"), \
                    patch.object(session, "wait_ready", side_effect=TimeoutError("Original boot failure")), \
                    patch.object(Path, "open", open_file):
                with self.assertRaisesRegex(TimeoutError, "Original boot failure"):
                    session.boot()
            self.assertEqual("Renderer receipt unavailable", session.report["graphics"]["receipt_error"])
            self.assertFalse(session.report["passed"])
            self.assertEqual("booting", session.report["status"])

    def test_graphics_receipt_records_native_selection_without_assuming_requested_backend(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=lambda: 0)
            raw = ("INFO         | Graphics backend: gfxstream\n"
                   "INFO         | emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle\n"
                   "INFO         | Selecting Vulkan device: SwiftShader Device (LLVM 10.0.0), Version: 1.3.0\n"
                   "INFO         | Graphics Adapter Vendor Google\n"
                   "INFO         | Graphics Adapter Android Emulator OpenGL ES Translator (ANGLE (SwiftShader))\n"
                   "INFO         | Graphics API Version OpenGL ES 3.0\n")
            (session.diagnostics / "emulator-stdout.txt").write_text(raw)
            session.capture_graphics_backend()
            graphics = session.report["graphics"]
            self.assertEqual("swiftshader_indirect", graphics["requested_selector"])
            self.assertEqual(["HostComposition"], graphics["requested_enabled_features"])
            self.assertEqual(["HVF", "Vulkan"], graphics["requested_disabled_features"])
            self.assertEqual("gfxstream", graphics["graphics_backend"])
            self.assertEqual("swiftshader", graphics["vulkan_mode"])
            self.assertEqual("swangle", graphics["gles_mode"])
            self.assertEqual("Android Emulator OpenGL ES Translator (ANGLE (SwiftShader))", graphics["adapter"])
            self.assertEqual("OpenGL ES 3.0", graphics["api_version"])
            self.assertEqual("emulator-stdout.txt", graphics["receipt_source"])
            self.assertTrue(graphics["backend_observed"])
            self.assertEqual(5, len(graphics["backend_receipt"]))
            self.assertFalse(session.report["passed"], "Observed renderer alone does not pass any device gate")

    def test_graphics_receipt_is_bounded_and_cannot_infer_backend_from_guest_or_help_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=lambda: 0)
            raw = ("supported modes: software lavapipe swiftshader swangle\n"
                   "[    0.000000] Graphics backend: gfxstream\n"
                   "I guest: emuglConfig_init: vulkan_mode_selected:lavapipe gles_mode_selected:swangle\n")
            with (session.diagnostics / "emulator-stdout.txt").open("w") as stream:
                stream.write(raw + "x" * (256 * 1024) + "\n")
                stream.write("INFO         | Graphics backend: excluded beyond bounded capture\n")
            session.capture_graphics_backend()
            graphics = session.report["graphics"]
            self.assertEqual(256 * 1024, graphics["receipt_bytes"])
            self.assertFalse(graphics["backend_observed"])
            self.assertEqual([], graphics["backend_receipt"])
            self.assertNotIn("graphics_backend", graphics)
            self.assertNotIn("vulkan_mode", graphics)
            self.assertNotIn("gles_mode", graphics)

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
        clock, deadlines, operations = Clock(), [], []
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=clock.now)
            emulator = Mock(pid=12345)
            emulator.poll.return_value = None
            (session.diagnostics / "guest-startup-logcat.txt").write_text(BOOT_DELIVERED)

            def ready(deadline):
                deadlines.append(deadline)
                operations.append("services")
                clock.pause(800 if clock.now() < 800 else 1)

            def resources(stage, deadline):
                deadlines.append(deadline)
                clock.pause(1)

            record_broadcast = session.record_boot_broadcast

            def broadcast(receipt):
                if receipt["completed"]:
                    operations.append("boot-completed")
                record_broadcast(receipt)

            def adb(*args, deadline, **kwargs):
                deadlines.append(deadline)
                operations.append("settings" if "settings" in args else "display" if "wm" in args else args[0])
                if "input" in args:
                    self.assertEqual(99, kwargs["timeout"], "Resource receipts consume the existing boot budget")
                if "settings" in args:
                    self.assertEqual(30, kwargs["timeout"], "Animation setting deadlines remain unchanged")
                if "dumpsys" not in args and "pidof" not in args and "logcat" not in args:
                    self.assertTrue(kwargs["check"])
                clock.pause(1)
                if "pidof" in args:
                    return reply("279")
                if "logcat" in args:
                    return reply(BOOT_DELIVERED)
                if "wm" in args:
                    return reply("Physical size: 360x640" if args[-1] == "size" else "Physical density: 140")
                return reply(UNLOCKED if "dumpsys" in args else "")

            with patch("software_emulator.subprocess.Popen", return_value=emulator), \
                    patch.object(session, "wait_ready", side_effect=ready), \
                    patch.object(session, "capture_host_resources", side_effect=resources), \
                    patch.object(session, "record_boot_broadcast", side_effect=broadcast), \
                    patch.object(session, "adb", side_effect=adb):
                session.boot()
            self.assertEqual("ready", session.report["status"])
            self.assertEqual([900] * 13, deadlines)
            self.assertFalse(session.report["passed"], "Ready emulator alone does not pass the controller")
            self.assertEqual({"width": 360, "height": 640, "density_dpi": 140}, session.report["display"])
            self.assertTrue(session.report["boot_broadcast"]["completed"])
            self.assertEqual(279, session.report["boot_broadcast"]["system_server_pid"])
            self.assertNotIn("logcat", operations, "Native capture avoids repeated guest log downloads")
            completed = operations.index("boot-completed")
            self.assertLess(completed, operations.index("settings"))
            self.assertLess(completed, len(operations) - 1 - operations[::-1].index("services"))
            self.assertLess(completed, operations.index("display"))
            calls = [json.loads(line) for line in (session.diagnostics / "boot-broadcast.jsonl").read_text().splitlines()]
            self.assertTrue(calls[-1]["completed"])

    def test_boot_delivery_timeout_retains_receipts_and_never_reaches_settings_or_controller(self):
        clock, commands = Clock(), []
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=clock.now)
            emulator = Mock(pid=12345, returncode=-15)
            emulator.poll.return_value = None
            (session.diagnostics / "guest-startup-logcat.txt").write_text(BOOT_DELIVERED)

            def ready(deadline):
                self.assertEqual(900, deadline)
                clock.pause(897)

            def adb(*args, deadline, **kwargs):
                self.assertEqual(900, deadline)
                commands.append(args)
                if "dumpsys" in args:
                    return reply(UNLOCKED)
                if "pidof" in args:
                    return reply("775")
                return reply(BOOT_DELIVERED if "logcat" in args else "")

            def wait(*args, **kwargs):
                return wait_for_boot_broadcast(*args, pause=clock.pause, **kwargs)

            with patch("software_emulator.subprocess.Popen", return_value=emulator), \
                    patch.object(session, "capture_host_resources"), \
                    patch.object(session, "wait_ready", side_effect=ready), \
                    patch.object(session, "adb", side_effect=adb), \
                    patch("software_emulator.wait_for_boot_broadcast", side_effect=wait):
                with self.assertRaisesRegex(TimeoutError, "Actual user0 BOOT_COMPLETED delivery"):
                    session.boot()
            self.assertEqual(900, clock.now())
            self.assertEqual("booting", session.report["status"])
            self.assertFalse(session.report["passed"])
            self.assertFalse(any("settings" in command or "wm" in command for command in commands))
            self.assertFalse(session.out.exists(), "Controller cannot be started after failed boot delivery")
            with patch.object(session, "adb", return_value=reply("raw final diagnostics")), \
                    patch("software_emulator.os.killpg"):
                session.close()
            receipts = [json.loads(line) for line in (session.out / "software-emulator/boot-broadcast.jsonl").read_text().splitlines()]
            self.assertTrue(receipts)
            self.assertTrue(all(not receipt["completed"] for receipt in receipts))
            self.assertEqual(775, receipts[-1]["system_server_pid"])
            self.assertTrue((session.out / "software-emulator/startup.json").exists())

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
        self.assertEqual("HostComposition,-HVF,-Vulkan", command[command.index("-feature") + 1])
        self.assertLess(command.index("-feature"), command.index("-qemu"))
        self.assertEqual("*:V", command[command.index("-logcat") + 1])
        self.assertEqual("/evidence/guest-startup-logcat.txt", command[command.index("-logcat-output") + 1])
        self.assertEqual("swiftshader_indirect", command[command.index("-gpu") + 1])
        self.assertEqual(1, command.count("-verbose"))
        self.assertLess(command.index("-verbose"), command.index("-qemu"))
        self.assertEqual(["-qemu", "-smp", "1"], command[-3:])
        self.assertEqual(1, command.count("-qemu"))
        self.assertEqual(1, command.count("-smp"))
        self.assertNotIn("-no-watchdog", command)

    def test_host_composition_request_cannot_stand_in_for_device_readiness(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = SoftwareSession(PROFILE, Path(temporary) / "evidence", "/sdk", now=lambda: 0)
            self.assertEqual(["HostComposition"], session.report["graphics"]["requested_enabled_features"])
            self.assertFalse(session.report["passed"])
            self.assertEqual("provisioning", session.report["status"])
            self.assertEqual(900, session.report["boot_timeout_seconds"])
            self.assertEqual(3600, session.deadline)
            self.assertNotIn("backend_observed", session.report["graphics"])

    def test_launcher_timezone_does_not_inherit_invalid_host_detection(self):
        with patch.dict("os.environ", {"TZ": "Unknown/Unknown"}):
            command = emulator_command(Path("/sdk"), Path("/evidence"))
        self.assertEqual(1, command.count("-timezone"))
        self.assertEqual("Etc/UTC", command[command.index("-timezone") + 1])
        self.assertNotIn("Unknown/Unknown", command)


class EmulatorPinTest(unittest.TestCase):
    """Use real ZIP/hash/XML/filesystem operations; fake only hosted commands."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.clock = Clock()
        self.session = SoftwareSession(PROFILE, self.root / "evidence", self.root / "sdk", now=self.clock.now)
        self.addCleanup(lambda: self.session.close() if self.session.diagnostics.exists() else None)
        self.original = self.session.sdk / "emulator"
        self.original.mkdir(parents=True)
        (self.original / "emulator").write_text("original installed executable")
        self.original_xml = b'''<r:repository xmlns:r="urn:repository" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:g="urn:generic">
<localPackage path="emulator"><type-details xsi:type="g:genericDetailsType"/>
<revision><major>37</major><minor>2</minor><micro>12</micro></revision></localPackage></r:repository>'''
        (self.original / "package.xml").write_bytes(self.original_xml)
        self.archive = self.root / "fixture.zip"
        self.calls = []
        self.version = "Android emulator version 32.1.15.0 (build_id 10696886) (CL:N/A)\n"
        self.installed_version = self.version
        self.gpu_help = reply("GPU modes: auto, host, swiftshader_indirect\n")
        self.feature_help = reply("-feature -HVF -feature Wifi\n")
        for target, value in (("software_emulator.platform.system", "Darwin"),
                              ("software_emulator.platform.machine", "arm64")):
            patcher = patch(target, return_value=value)
            patcher.start()
            self.addCleanup(patcher.stop)
        pin = patch.dict(EMULATOR_PIN)
        pin.start()
        self.addCleanup(pin.stop)
        self.write_archive()
        mocked = patch.object(self.session, "run", side_effect=self.host_command)
        self.mock_run = mocked.start()
        self.addCleanup(mocked.stop)

    def write_archive(self, extra=(), properties=None):
        properties = properties or "Pkg.Revision=32.1.15\nPkg.BuildId=10696886\nPkg.Path=emulator\n"
        entries = [("emulator/", b"", stat.S_IFDIR | 0o755),
                   ("emulator/emulator", b"pinned executable", stat.S_IFREG | 0o755),
                   ("emulator/qemu/darwin-aarch64/qemu-system-aarch64-headless", b"qemu", stat.S_IFREG | 0o755),
                   ("emulator/source.properties", properties.encode(), stat.S_IFREG | 0o644), *extra]
        with zipfile.ZipFile(self.archive, "w") as archive:
            for name, content, mode in entries:
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = mode << 16
                archive.writestr(info, content)
        EMULATOR_PIN.update(size_bytes=self.archive.stat().st_size,
                            sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest())

    def host_command(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if command[0] == "curl":
            shutil.copyfile(self.archive, command[command.index("--output") + 1])
        elif command[-1] == "-version":
            return reply(self.installed_version if command[0] == str(self.original / "emulator") else self.version)
        elif command[-1] == "-help-gpu":
            return self.gpu_help
        elif command[-1] == "-help-feature":
            return self.feature_help
        elif "create" in command:
            config = self.session.avd_home / "jarvis-api29-software.avd/config.ini"
            config.parent.mkdir()
            config.write_text("hw.cpu.ncore=1\nhw.ramSize=1536M\nvm.heapSize=256M\n"
                              "hw.lcd.width=1080\nhw.lcd.height=1920\nhw.lcd.density=420\n")
        return reply("")

    def assert_original_retained(self):
        self.assertEqual("original installed executable", (self.original / "emulator").read_text())
        self.assertEqual(self.original_xml, (self.original / "package.xml").read_bytes())
        self.assertFalse(list(self.session.sdk.glob("jarvis-emulator-*")))
        self.assertFalse(self.session.report["emulator_pin"]["verified"])
        self.assertFalse(self.session.report["passed"])

    def test_provision_preserves_guest_and_settings_and_publishes_verified_pin_before_avd(self):
        self.session.provision()
        commands = [command for command, _ in self.calls]
        image = "system-images;android-29;default;arm64-v8a"
        installed = next(command for command in commands if image in command and "--install" in command)
        created = next(command for command in commands if "create" in command)
        curl = next(command for command in commands if command[0] == "curl")
        self.assertIn("--channel=0", installed)
        self.assertEqual(image, created[created.index("--package") + 1])
        self.assertLess(commands.index(installed), commands.index(curl))
        version_checks = [command for command in commands if command[-1] == "-version"]
        self.assertEqual(2, len(version_checks))
        self.assertLess(commands.index(version_checks[-1]), commands.index(created))
        self.assertFalse(any("--install" in command and "emulator" in command
                             for command in commands[commands.index(curl):]))
        self.assertFalse(any("google_apis" in argument for command in commands for argument in command))
        self.assertEqual("=https", curl[curl.index("--proto-redir") + 1])
        self.assertEqual(str(EMULATOR_PIN["size_bytes"]), curl[curl.index("--max-filesize") + 1])
        for command, kwargs in self.calls[commands.index(curl):commands.index(created)]:
            self.assertEqual(600, kwargs["deadline"])
        self.assertEqual(0o755, stat.S_IMODE((self.original / "emulator").stat().st_mode))
        self.assertEqual(0o755, stat.S_IMODE((self.original / "qemu/darwin-aarch64/qemu-system-aarch64-headless").stat().st_mode))
        document = minidom.parseString((self.original / "package.xml").read_bytes())
        self.assertEqual("urn:generic", document.documentElement.getAttribute("xmlns:g"))
        self.assertEqual(["32", "1", "15"], [document.getElementsByTagName(name)[0].firstChild.data
                                             for name in ("major", "minor", "micro")])
        proof = self.session.report["emulator_pin"]
        self.assertTrue(proof["verified"])
        self.assertEqual(EMULATOR_PIN["sha256"], proof["actual_sha256"])
        self.assertEqual("10696886", proof["source_properties"]["Pkg.BuildId"])
        self.assertEqual(self.version, proof["installed_version_output"])
        self.assertTrue((self.session.diagnostics / "emulator-package.xml").exists())
        config = (self.session.diagnostics / "avd-config.ini").read_text()
        for setting in ("hw.cpu.ncore=1", "hw.ramSize=2048M", "vm.heapSize=256M", "hw.lcd.width=360",
                        "hw.lcd.height=640", "hw.lcd.density=140", "disk.dataPartition.size=4096M"):
            self.assertIn(setting, config)
        self.assertEqual(3600, self.session.deadline)
        self.assertFalse(self.session.report["passed"], "Provisioning does not establish test coverage")

    def test_corrupt_or_truncated_archive_is_rejected_before_extraction(self):
        valid = self.archive.read_bytes()
        for corrupt in (valid[:-1], valid[:-1] + bytes([valid[-1] ^ 1])):
            with self.subTest(size=len(corrupt)):
                self.archive.write_bytes(corrupt)
                with patch("software_emulator.extract_emulator") as extract:
                    with self.assertRaisesRegex(ValueError, "archive (size|SHA256)"):
                        self.session.pin_emulator()
                    extract.assert_not_called()
                self.assert_original_retained()

    def test_unsafe_archive_paths_symlinks_and_case_collisions_never_publish(self):
        entries = (("emulator/../../escaped", b"bad", stat.S_IFREG | 0o644),
                   ("/emulator/escaped", b"bad", stat.S_IFREG | 0o644),
                   ("emulator\\escaped", b"bad", stat.S_IFREG | 0o644),
                   ("emulator/link", b"../../escaped", stat.S_IFLNK | 0o777),
                   ("emulator/EMULATOR", b"collision", stat.S_IFREG | 0o755))
        for entry in entries:
            with self.subTest(entry=entry[0]):
                self.write_archive([entry])
                with self.assertRaisesRegex(ValueError, "Unsafe emulator archive entry"):
                    self.session.pin_emulator()
                self.assert_original_retained()
                self.assertFalse((self.root / "escaped").exists())

    def test_metadata_and_executable_version_must_both_match_official_pin(self):
        self.write_archive(properties="Pkg.Revision=37.2.12\nPkg.BuildId=10696886\nPkg.Path=emulator\n")
        with self.assertRaisesRegex(ValueError, "source.properties"):
            self.session.pin_emulator()
        self.assertFalse(any(command[-1] == "-version" for command, _ in self.calls))
        self.assert_original_retained()
        self.write_archive()
        for version in (self.version.replace("32.1.15", "37.2.12"),
                        self.version.replace("10696886", "16199999"), self.version * 2):
            with self.subTest(version=version):
                self.version = version
                with self.assertRaisesRegex(ValueError, "actual emulator version"):
                    self.session.pin_emulator()
                self.assert_original_retained()

    def test_invalid_package_xml_fails_before_running_candidate(self):
        (self.original / "package.xml").write_text('<repository><localPackage path="other"/></repository>')
        with self.assertRaisesRegex(ValueError, "package.xml"):
            self.session.pin_emulator()
        self.assertFalse(any(command[-1] == "-version" for command, _ in self.calls))
        self.assertEqual("original installed executable", (self.original / "emulator").read_text())
        self.assertFalse(self.session.report["emulator_pin"]["verified"])

    def test_failed_installed_version_check_restores_original_sdk_emulator(self):
        self.installed_version = self.version.replace("32.1.15", "37.2.12")
        with self.assertRaisesRegex(ValueError, "actual emulator version"):
            self.session.pin_emulator()
        self.assert_original_retained()

    def test_native_help_is_a_bounded_receipt_not_an_exhaustive_capability_gate(self):
        for help_result in (reply("GPU modes: auto, host, swiftshader_indirect\n"),
                            reply("GPU modes: software, lavapipe, swangle\n", code=0),
                            reply("partial native help", code=124)):
            with self.subTest(code=help_result.returncode, output=help_result.stdout):
                self.gpu_help = help_result
                self.feature_help = reply("raw feature usage", code=help_result.returncode)
                self.session.pin_emulator()
                command, kwargs = next((command, kwargs) for command, kwargs in reversed(self.calls)
                                       if command[-1] == "-help-gpu")
                self.assertEqual([str(self.original / "emulator"), "-help-gpu"], command)
                self.assertEqual({"deadline": 600, "timeout": 15, "check": False}, kwargs)
                receipt = self.session.report["graphics"]["native_help"]
                self.assertEqual(help_result.stdout, receipt["stdout"])
                self.assertEqual(help_result.stderr, receipt["stderr"])
                self.assertEqual(help_result.returncode, receipt["exit_code"])
                self.assertEqual("software" in help_result.stdout, receipt["mentions_software"])
                command, kwargs = next((command, kwargs) for command, kwargs in reversed(self.calls)
                                       if command[-1] == "-help-feature")
                self.assertEqual([str(self.original / "emulator"), "-help-feature"], command)
                self.assertEqual({"deadline": 600, "timeout": 15, "check": False}, kwargs)
                self.assertEqual({"stdout": self.feature_help.stdout,
                                  "stderr": self.feature_help.stderr,
                                  "exit_code": self.feature_help.returncode},
                                 self.session.report["graphics"]["native_feature_help"])
                self.assertTrue(self.session.report["emulator_pin"]["verified"])
                self.assertFalse(self.session.report["passed"])

    def test_native_gpu_help_cannot_renew_remaining_pin_budget_or_leave_replacement_after_expiry(self):
        self.session.deadline = 10

        def help_expires(command, **kwargs):
            self.assertEqual(10, kwargs["deadline"])
            result = self.host_command(command, **kwargs)
            if command[-1] == "-help-gpu":
                self.clock.pause(10)
            return result

        self.mock_run.side_effect = help_expires
        with self.assertRaisesRegex(TimeoutError, "deadline expired"):
            self.session.pin_emulator()
        self.assertEqual(10, self.clock.now())
        self.assert_original_retained()
        self.assertFalse(any(command[-1] == "-help-feature" for command, _ in self.calls))

    def test_native_feature_help_expiry_restores_original_without_renewing_pin_budget(self):
        self.session.deadline = 10

        def help_expires(command, **kwargs):
            self.assertEqual(10, kwargs["deadline"])
            result = self.host_command(command, **kwargs)
            if command[-1] == "-help-feature":
                self.clock.pause(10)
            return result

        self.mock_run.side_effect = help_expires
        with self.assertRaisesRegex(TimeoutError, "deadline expired"):
            self.session.pin_emulator()
        self.assertEqual(10, self.clock.now())
        self.assert_original_retained()

    def test_pin_download_and_verification_share_remaining_provisioning_budget(self):
        self.session.deadline = 25

        def expired_download(command, **kwargs):
            self.assertEqual(25, kwargs["deadline"])
            self.assertEqual("25", command[command.index("--max-time") + 1])
            result = self.host_command(command, **kwargs)
            self.clock.pause(25)
            return result

        self.mock_run.side_effect = expired_download
        with self.assertRaisesRegex(TimeoutError, "deadline expired"):
            self.session.pin_emulator()
        self.assertEqual(1, self.mock_run.call_count)
        self.assert_original_retained()

    def test_invalid_pin_stops_main_before_boot_or_controller_and_retains_failure(self):
        self.version = self.version.replace("32.1.15", "37.2.12")
        arguments = ["software_emulator.py", "--profile", PROFILE["id"], "--out", str(self.session.out),
                     "--", "release-controller"]
        with patch.object(sys, "argv", arguments), \
                patch.dict("os.environ", {"ANDROID_HOME": str(self.session.sdk)}), \
                patch("software_emulator.SoftwareSession", return_value=self.session), \
                patch("software_emulator.subprocess.Popen") as boot, \
                patch("software_emulator.subprocess.run") as controller:
            self.assertEqual(1, main())
        boot.assert_not_called()
        controller.assert_not_called()
        report = json.loads((self.session.out / "software-emulator/startup.json").read_text())
        self.assertEqual("failed", report["status"])
        self.assertFalse(report["passed"])
        self.assertFalse(report["emulator_pin"]["verified"])
        self.assertIn("actual emulator version", report["errors"][0])


if __name__ == "__main__":
    unittest.main()
