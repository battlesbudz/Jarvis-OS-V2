#!/usr/bin/env python3
"""Drive a disposable Android emulator and retain evidence even when verification fails."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.battlesbudz.jarvis.v2"
ACTIVITY = f"{PACKAGE}/.MainActivity"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
SCENARIOS = Path(__file__).with_name("scenarios.json")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def instrumentation_results(output, expected, expected_class):
    """adb exits zero on failed tests; require one explicit pass for every named test."""
    status, completed, errors = {}, {}, []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = int(line.rsplit(":", 1)[1].strip())
            if code != 1:
                name = status.get("test", "<unknown>")
                if name in completed or status.get("class") != expected_class:
                    errors.append(f"Unexpected/duplicate test result: {status}")
                completed[name] = code
            status = {}
    if set(completed) != set(expected):
        errors.append(f"Expected {sorted(expected)}, received {sorted(completed)}")
    if any(code != 0 for code in completed.values()):
        errors.append("Failed, skipped or incomplete instrumentation test")
    if not re.search(r"OK \(\d+ tests?\)", output):
        errors.append("Missing successful runner completion")
    if any(marker in output for marker in ("INSTRUMENTATION_FAILED", "FAILURES!!!", "Process crashed", "shortMsg=")):
        errors.append("Instrumentation reported a failure")
    return {"passed": not errors, "tests": completed, "errors": errors}


class Device:
    def __init__(self, serial, out, adb="adb"):
        self.serial, self.out, self.adb = serial, Path(out), adb
        self.out.mkdir(parents=True, exist_ok=True)

    def run(self, *args, timeout=60, check=True, binary=False):
        command = [self.adb, "-s", self.serial, *map(str, args)]
        started = time.monotonic()
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        with (self.out / "commands.jsonl").open("a") as log:
            log.write(json.dumps({"argv": command, "exit": result.returncode,
                                  "seconds": round(time.monotonic() - started, 2)}) + "\n")
        if check and result.returncode:
            raise RuntimeError(f"adb {args[0]} failed: {result.stderr.decode(errors='replace')}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, *args, **kwargs):
        # adb joins shell arguments remotely; quote even though local shell=False.
        return self.run("shell", shlex.join(map(str, args)), **kwargs)

    def snapshot(self, name):
        self.out.joinpath(f"{name}.png").write_bytes(self.run("exec-out", "screencap", "-p", binary=True))
        self.shell("uiautomator", "dump", "/sdcard/jarvis-window.xml")
        xml = self.shell("cat", "/sdcard/jarvis-window.xml")
        self.out.joinpath(f"{name}.xml").write_text(xml)
        ET.fromstring(xml)  # A failed hierarchy dump is not valid evidence.
        return xml


def verify(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    device = Device(args.serial, out, args.adb)
    scenarios = json.loads(SCENARIOS.read_text())
    evidence_folder = f"jarvis-verification-{time.time_ns()}"
    report = {"schema": 1, "passed": False, "source_commit": args.source_commit,
              "pr_head": args.pr_head, "run_url": os.getenv("GITHUB_SERVER_URL", "https://github.com") + "/" +
              os.getenv("GITHUB_REPOSITORY", "") + "/actions/runs/" + os.getenv("GITHUB_RUN_ID", ""),
              "coverage": "release UI + Android actions; no model inference",
              "not_covered": scenarios["not_covered"], "device_evidence_folder": evidence_folder, "errors": []}

    def collect_test_evidence():
        device.run("pull", f"/sdcard/Download/{evidence_folder}", str(out / "tests"))
        for name in scenarios["tests"]:
            for extension in ("png", "xml"):
                files = list((out / "tests").rglob(f"{name}.{extension}"))
                if len(files) != 1 or files[0].stat().st_size == 0:
                    raise RuntimeError(f"Missing or duplicate evidence for {name}.{extension}")
                if extension == "xml":
                    ET.parse(files[0])
                elif not files[0].read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError(f"Invalid screenshot for {name}")
    try:
        # Reset is intentionally restricted to an emulator; never clear a user's phone.
        if not args.allow_emulator_reset or device.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Verification requires a disposable emulator and --allow-emulator-reset")
        if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
            raise RuntimeError("A full source commit SHA is required")
        report["apk_sha256"] = sha256(args.apk)
        report["test_apk_sha256"] = sha256(args.test_apk)
        report["device"] = {key: device.shell("getprop", prop).strip() for key, prop in
                            (("abi", "ro.product.cpu.abilist"), ("api", "ro.build.version.sdk"),
                             ("fingerprint", "ro.build.fingerprint"), ("native_bridge", "ro.dalvik.vm.native.bridge"))}
        if "arm64-v8a" not in report["device"]["abi"]:
            raise RuntimeError("Emulator cannot run the release ARM64 APK: missing arm64-v8a ABI/native bridge")
        device.run("install", "-r", args.apk, timeout=180)
        device.run("install", "-r", "-t", args.test_apk, timeout=180)
        if "Success" not in device.shell("pm", "clear", PACKAGE):
            raise RuntimeError("App data reset failed")
        device.run("logcat", "-c")
        # Permissions remain ungranted: first-run UI must function without microphone access.
        device.shell("dumpsys", "battery", "set", "level", "73")
        device.shell("input", "keyevent", "KEYCODE_WAKEUP")
        device.shell("wm", "dismiss-keyguard", check=False)
        device.shell("am", "start", "-W", "-n", ACTIVITY)
        device.snapshot("first-launch")
        try:
            output = device.shell("am", "instrument", "-w", "-r", "-e", "class", scenarios["class"],
                                  "-e", "jarvisEvidenceDir", evidence_folder, RUNNER, timeout=600)
        except subprocess.TimeoutExpired as error:
            (out / "instrumentation.txt").write_bytes(error.stdout or b"")
            raise RuntimeError("Instrumentation exceeded the 10-minute limit") from error
        (out / "instrumentation.txt").write_text(output)
        report["instrumentation"] = instrumentation_results(output, scenarios["tests"], scenarios["class"])
        if not report["instrumentation"]["passed"]:
            raise RuntimeError("Instrumentation failed; see instrumentation.txt and per-test screenshots")
        device.shell("am", "force-stop", PACKAGE)
        device.shell("am", "start", "-W", "-n", ACTIVITY)
        for _ in range(6):
            xml = device.snapshot("after-process-restart")
            texts = [node.get("text") for node in ET.fromstring(xml).iter("node")]
            if scenarios["restart_model"] in texts:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Model selection did not survive a fresh app process")
        report["process_restart"] = "passed"
        report["passed"] = True
    except (OSError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        report["errors"].append(str(error))
    finally:
        for name, collect in (
            ("final screenshot", lambda: device.snapshot("final")),
            ("logcat", lambda: (out / "logcat.txt").write_text(device.run("logcat", "-d", "-v", "threadtime", timeout=30))),
            ("per-test evidence", collect_test_evidence),
            ("package metadata", lambda: (out / "package.txt").write_text(device.shell("dumpsys", "package", PACKAGE))),
        ):
            try:
                collect()
            except Exception as error:
                report["errors"].append(f"Could not collect {name}: {error}")
                report["passed"] = False
        try:
            device.shell("dumpsys", "battery", "reset", check=False)
        except Exception:
            pass
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        (out / "summary.md").write_text(
            f"Jarvis verification: {'PASS' if report['passed'] else 'FAIL'}\n\n"
            f"Source: `{args.source_commit}`\n\nScope: {report['coverage']}\n\n" +
            "\n".join(f"- {error}" for error in report["errors"]) +
            "\n\nNot verified:\n" + "\n".join(f"- {item}" for item in report["not_covered"]) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("--apk", required=True)
    run.add_argument("--test-apk", required=True)
    run.add_argument("--out", required=True, help="New evidence directory; existing evidence is never overwritten")
    run.add_argument("--source-commit", required=True)
    run.add_argument("--pr-head", default="")
    run.add_argument("--allow-emulator-reset", action="store_true")
    control = sub.add_parser("control", help="Interactive controls for a running emulator; never resets app data")
    control.add_argument("action", choices=["snapshot", "launch", "tap", "swipe", "text", "back"])
    control.add_argument("values", nargs="*")
    control.add_argument("--out", required=True)
    args = parser.parse_args()
    if args.command == "run":
        return verify(args)
    device = Device(args.serial, args.out, args.adb)
    if args.action == "snapshot":
        device.snapshot(f"screen-{time.time_ns()}")
    elif args.action == "launch":
        device.shell("am", "start", "-W", "-n", ACTIVITY)
    elif args.action == "back":
        device.shell("input", "keyevent", "KEYCODE_BACK")
    elif args.action in ("tap", "swipe"):
        count = 2 if args.action == "tap" else 5
        if len(args.values) != count or not all(value.isdecimal() for value in args.values):
            parser.error(f"{args.action} requires {count} nonnegative integers")
        device.shell("input", args.action, *args.values)
    else:
        if len(args.values) != 1:
            parser.error("text requires one quoted string")
        device.shell("input", "text", args.values[0].replace(" ", "%s"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
