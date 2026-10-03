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
import queue
import threading
import zipfile
import xml.etree.ElementTree as ET

try:
    from .profiles import load_profiles
except ImportError:
    from profiles import load_profiles

PACKAGE = "com.battlesbudz.jarvis.v2"
ACTIVITY = f"{PACKAGE}/.MainActivity"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
SCENARIOS = Path(__file__).with_name("scenarios.json")
LIFECYCLE_SCENARIOS = Path(__file__).with_name("lifecycle_scenarios.json")
LAYOUT_SCENARIOS = Path(__file__).with_name("layout_scenarios.json")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def instrumentation_results(output, expected, expected_class):
    """adb exits zero on failed tests; require one explicit pass for every named test."""
    status, completed, errors = {}, {}, []
    if not expected or len(set(expected)) != len(expected):
        errors.append("Expected test contract must be nonempty and unique")
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.rsplit(":", 1)[1].strip())
            except ValueError:
                errors.append("Invalid instrumentation status code")
                status = {}
                continue
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
    completions = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    if completions != [str(len(expected))]:
        errors.append("Missing/duplicate successful runner completion or wrong test count")
    if any(marker in output for marker in ("INSTRUMENTATION_FAILED", "FAILURES!!!", "Process crashed", "shortMsg=")):
        errors.append("Instrumentation reported a failure")
    return {"passed": not errors, "tests": completed, "errors": errors}


def interrupted_results(output, test, expected_class, boundary):
    """Only a declared external-death phase may lack a JUnit completion."""
    errors = []
    marker = f"INSTRUMENTATION_STATUS: jarvisBoundary={boundary}"
    if output.splitlines().count(marker) != 1:
        errors.append("Expected exactly one acknowledged interruption boundary")
    if f"INSTRUMENTATION_STATUS: class={expected_class}" not in output or f"INSTRUMENTATION_STATUS: test={test}" not in output:
        errors.append("Missing named test start at interruption boundary")
    status, starts, observed = {}, 0, False
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            status[key] = value
            if key == "jarvisBoundary":
                if starts != 1:
                    errors.append("Interruption boundary did not follow a unique named test start")
                observed = True
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.rsplit(":", 1)[1].strip())
            except ValueError:
                errors.append("Invalid interrupted instrumentation status")
                status = {}
                continue
            if code == 1 and "test" in status:
                starts += 1
                if status.get("class") != expected_class or status.get("test") != test or observed:
                    errors.append("Unexpected named test in interrupted phase")
            elif code in (0, -2, -3) or code != 1 and not observed:
                errors.append("Interrupted phase completed, failed, skipped or ended before its boundary")
            status = {}
    if starts != 1:
        errors.append("Missing or duplicate interrupted test start")
    if re.search(r"^OK \(\d+ tests?\)", output, re.MULTILINE) or "FAILURES!!!" in output:
        errors.append("An interrupted phase cannot finish or report a test assertion failure")
    return {"passed": not errors, "tests": {test: "interrupted"}, "errors": errors,
            "boundary_observed": not errors, "interrupted": True}


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

    def copy_phase_boundary(self, test, evidence_folder, boundary):
        """Reuse evidence already captured by the active instrumentation UiAutomation.

        Starting a second `uiautomator dump` would conflict with that registered
        session. adb sync can safely copy the exported files before process death.
        """
        if (not re.fullmatch(r"[A-Za-z0-9_]+", test) or
                not re.fullmatch(r"jarvis-verification-[0-9]+", evidence_folder) or
                boundary not in ("process_kill", "permission_revoke")):
            raise RuntimeError("Invalid phase boundary evidence identity")
        for extension in ("png", "xml"):
            target = self.out / f"boundary-{boundary}.{extension}"
            self.run("pull", f"/sdcard/Download/{evidence_folder}/{test}.{extension}", str(target))
            if not target.is_file() or target.stat().st_size == 0:
                raise RuntimeError(f"Missing pre-interruption {extension} evidence")
            if extension == "png":
                if not target.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError("Invalid pre-interruption screenshot")
            else:
                ET.parse(target)

    def instrument(self, test_class, tests, evidence_folder, timeout=600, boundary=None, foldable=False, expected_page_size=4096):
        """Stream explicit test handshakes so adb can interrupt/change the real target."""
        selector = test_class if len(tests) != 1 else f"{test_class}#{tests[0]}"
        args = ["am", "instrument", "-w", "-r", "-e", "class", selector,
                "-e", "jarvisEvidenceDir", evidence_folder, "-e", "jarvisFoldable", str(foldable).lower(),
                "-e", "jarvisExpectedPageSize", str(expected_page_size), RUNNER]
        command = [self.adb, "-s", self.serial, "shell", shlex.join(args)]
        started = time.monotonic()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        lines, events = [], []
        incoming = queue.Queue()

        def read_lines():
            for line in process.stdout:
                incoming.put(line)
            incoming.put(None)

        reader = threading.Thread(target=read_lines, daemon=True)
        reader.start()
        try:
            ended = False
            while not ended:
                if time.monotonic() - started > timeout:
                    raise RuntimeError(f"Instrumentation exceeded {timeout} seconds")
                try:
                    line = incoming.get(timeout=.1)
                except queue.Empty:
                    continue
                if line is None:
                    ended = True
                    continue
                lines.append(line)
                stripped = line.strip()
                if stripped.startswith("INSTRUMENTATION_STATUS: jarvisBoundary="):
                    received = stripped.split("=", 1)[1]
                    if received != boundary or events:
                        raise RuntimeError("Unexpected or duplicate external interruption request")
                    pid = self.shell("pidof", PACKAGE).strip()
                    if not re.fullmatch(r"\d+", pid):
                        raise RuntimeError("No single live app process at interruption boundary")
                    self.copy_phase_boundary(tests[0], evidence_folder, received)
                    if received == "process_kill":
                        self.shell("am", "force-stop", PACKAGE)
                    elif received == "permission_revoke":
                        self.shell("pm", "revoke", PACKAGE, "android.permission.RECORD_AUDIO")
                    else:
                        raise RuntimeError("Unsupported interruption boundary")
                    for _ in range(100):
                        remaining = self.shell("pidof", PACKAGE, check=False).strip()
                        if not remaining:
                            break
                        time.sleep(.1)
                    else:
                        raise RuntimeError("External interruption did not terminate the app process")
                    events.append({"boundary": received, "pid_before": int(pid), "pid_after": None})
                elif stripped.startswith("INSTRUMENTATION_STATUS: jarvisFold="):
                    posture = stripped.split("=", 1)[1]
                    if not foldable or posture not in ("fold", "unfold"):
                        raise RuntimeError("Unexpected emulator posture request")
                    self.run("emu", posture)
                    events.append({"posture": posture})
            process.wait(timeout=30)
            if process.returncode and not boundary:
                raise RuntimeError(f"Instrumentation adb exited {process.returncode}")
            output = "".join(lines)
            if boundary:
                result = interrupted_results(output, tests[0], test_class, boundary)
                if len(events) != 1:
                    result["errors"].append("Controller did not perform the declared external interruption")
                    result["passed"] = False
                else:
                    result.update(events[0])
            else:
                result = instrumentation_results(output, tests, test_class)
            return output, result, events
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            with (self.out / "commands.jsonl").open("a") as log:
                log.write(json.dumps({"argv": command, "exit": process.returncode,
                                      "seconds": round(time.monotonic() - started, 2)}) + "\n")
            # Keep partial output if infrastructure, a handshake or a timeout fails.
            self.out.joinpath("last-instrumentation.txt").write_text("".join(lines))

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
    lifecycle = json.loads(LIFECYCLE_SCENARIOS.read_text())
    layout = json.loads(LAYOUT_SCENARIOS.read_text())
    profile = next((p for p in load_profiles() if p["id"] == args.profile), None)
    evidence_folder = f"jarvis-verification-{time.time_ns()}"
    report = {"schema": 1, "passed": False, "source_commit": args.source_commit,
              "pr_head": args.pr_head, "run_url": os.getenv("GITHUB_SERVER_URL", "https://github.com") + "/" +
              os.getenv("GITHUB_REPOSITORY", "") + "/actions/runs/" + os.getenv("GITHUB_RUN_ID", ""),
              "coverage": "release UI + Android actions + previous-APK upgrade + external process/permission recovery + layout/accessibility; no model inference",
              "not_covered": scenarios["not_covered"], "device_evidence_folder": evidence_folder, "errors": []}
    report["profile"] = profile
    evidence_tests = (list(scenarios["tests"]) + list(layout["tests"]) +
                      [phase["test"] for phase in lifecycle["upgrade"].values()] +
                      [phase["test"] for phase in lifecycle["phases"].values()])

    def instrument_phase(contract, target, *, boundary=None, foldable=False, timeout=180):
        test_class = contract.get("class", lifecycle["class"])
        tests = contract.get("tests", [contract.get("test")])
        path = out / target
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            output, result, events = device.instrument(test_class, tests, evidence_folder,
                                                       timeout=timeout, boundary=boundary, foldable=foldable,
                                                       expected_page_size=profile["page_size"])
        except Exception:
            partial = out / "last-instrumentation.txt"
            if partial.is_file():
                path.write_bytes(partial.read_bytes())
            raise
        path.write_text(output)
        if not result["passed"]:
            raise RuntimeError(f"Instrumentation phase {target} failed: {result['errors']}")
        return result, events

    def package_version(target):
        value = device.shell("dumpsys", "package", PACKAGE)
        (out / target).write_text(value)
        matches = re.findall(r"\bversionCode=(\d+)\b", value)
        if len(set(matches)) != 1:
            raise RuntimeError("Installed package has no unambiguous version code")
        return int(matches[0])

    def collect_test_evidence():
        device.run("pull", f"/sdcard/Download/{evidence_folder}", str(out / "tests"))
        for name in evidence_tests:
            for extension in ("png", "xml"):
                files = list((out / "tests").rglob(f"{name}.{extension}"))
                if len(files) != 1 or files[0].stat().st_size == 0:
                    raise RuntimeError(f"Missing or duplicate evidence for {name}.{extension}")
                if extension == "xml":
                    ET.parse(files[0])
                elif not files[0].read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError(f"Invalid screenshot for {name}")
        native_files = list((out / "tests").rglob("test04_nativeLibrariesLoadAtExpectedPageSize-native.json"))
        if len(native_files) != 1:
            raise RuntimeError("Missing or duplicate native runtime page-size evidence")
        native = json.loads(native_files[0].read_text())
        with zipfile.ZipFile(args.apk) as apk:
            libraries = sorted(name.rsplit("/", 1)[1].removeprefix("lib").removesuffix(".so")
                               for name in apk.namelist() if name.startswith("lib/arm64-v8a/") and name.endswith(".so"))
        if (native.get("passed") is not True or native.get("page_size") != profile["page_size"] or
                native.get("expected_page_size") != profile["page_size"] or not libraries or
                sorted(native.get("shipping_libraries", [])) != libraries or
                sorted(native.get("loaded_libraries", [])) != libraries):
            raise RuntimeError("Runtime loading evidence disagrees with shipping APK libraries/page size")
        report["layout"]["native_loading"] = native
    try:
        # Reset is intentionally restricted to an emulator; never clear a user's phone.
        if not args.allow_emulator_reset or device.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Verification requires a disposable emulator and --allow-emulator-reset")
        if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
            raise RuntimeError("A full source commit SHA is required")
        if profile is None:
            raise RuntimeError("A declared required emulator --profile is required")
        report["apk_sha256"] = sha256(args.apk)
        report["test_apk_sha256"] = sha256(args.test_apk)
        report["device"] = {key: device.shell("getprop", prop).strip() for key, prop in
                            (("abi", "ro.product.cpu.abilist"), ("api", "ro.build.version.sdk"),
                             ("fingerprint", "ro.build.fingerprint"), ("native_bridge", "ro.dalvik.vm.native.bridge"))}
        report["device"]["page_size"] = int(device.shell("getconf", "PAGE_SIZE").strip())
        if int(report["device"]["api"]) != profile["api"] or report["device"]["page_size"] != profile["page_size"]:
            raise RuntimeError("Provisioned emulator API/page size disagrees with its required profile")
        if "arm64-v8a" not in report["device"]["abi"]:
            raise RuntimeError("Emulator cannot run the release ARM64 APK: missing arm64-v8a ABI/native bridge")
        # Seed OLD installed application storage, then replace package bytes without reset.
        # Android install -r also requires the original package signing identity.
        report["upgrade"] = {"passed": False, "previous_apk_sha256": sha256(args.previous_apk)}
        previous_metadata = json.loads(Path(args.previous_metadata).read_text())
        if (previous_metadata.get("schema") != 1 or previous_metadata.get("sha256") != report["upgrade"]["previous_apk_sha256"] or
                not isinstance(previous_metadata.get("build"), int) or isinstance(previous_metadata["build"], bool) or
                not previous_metadata.get("tag") or not previous_metadata.get("repository")):
            raise RuntimeError("Previous release provenance does not match its APK bytes")
        report["upgrade"]["previous_release"] = previous_metadata
        (out / "upgrade").mkdir()
        (out / "upgrade" / "previous-release.json").write_text(json.dumps(previous_metadata, indent=2) + "\n")
        device.run("uninstall", PACKAGE, check=False)
        device.run("uninstall", PACKAGE + ".test", check=False)
        device.run("install", args.previous_apk, timeout=180)
        device.run("install", "-r", "-t", args.test_apk, timeout=180)
        if "Success" not in device.shell("pm", "clear", PACKAGE):
            raise RuntimeError("Previous-release fixture data reset failed")
        report["upgrade"]["previous_version_code"] = package_version("upgrade/previous-package.txt")
        if report["upgrade"]["previous_version_code"] != previous_metadata["build"]:
            raise RuntimeError("Previous installed APK version disagrees with its numbered published release")
        report["upgrade"]["seed"], _ = instrument_phase(lifecycle["upgrade"]["seed"], "upgrade/seed.txt")
        device.shell("am", "force-stop", PACKAGE)
        device.run("install", "-r", args.apk, timeout=180)
        report["upgrade"]["candidate_version_code"] = package_version("upgrade/candidate-package.txt")
        if report["upgrade"]["candidate_version_code"] <= report["upgrade"]["previous_version_code"]:
            raise RuntimeError("Candidate is not a newer APK than the previous release")
        report["upgrade"]["verify"], _ = instrument_phase(lifecycle["upgrade"]["verify"], "upgrade/verify.txt")
        report["upgrade"]["data_cleared_during_update"] = False
        report["upgrade"]["passed"] = True
        # Existing isolated fresh-install journeys remain an independent gate.
        if "Success" not in device.shell("pm", "clear", PACKAGE):
            raise RuntimeError("Candidate fresh-install data reset failed")
        device.run("logcat", "-c")
        # Permissions remain ungranted: first-run UI must function without microphone access.
        device.shell("dumpsys", "battery", "set", "level", "73")
        device.shell("input", "keyevent", "KEYCODE_WAKEUP")
        device.shell("wm", "dismiss-keyguard", check=False)
        device.shell("am", "start", "-W", "-n", ACTIVITY)
        device.snapshot("first-launch")
        report["instrumentation"], _ = instrument_phase(scenarios, "instrumentation.txt", timeout=900)
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
        report["lifecycle"] = {"passed": False, "phases": {},
                               "notification_permission": "runtime" if profile["api"] >= 33 else "platform_not_applicable"}
        device.shell("pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        for phase in ("process_seed", "process_verify"):
            contract = lifecycle["phases"][phase]
            report["lifecycle"]["phases"][phase], _ = instrument_phase(
                contract, f"lifecycle/{phase}.txt", boundary=contract.get("interrupt"))
        device.shell("pm", "revoke", PACKAGE, "android.permission.RECORD_AUDIO")
        report["lifecycle"]["phases"]["microphone_denied"], _ = instrument_phase(
            lifecycle["phases"]["microphone_denied"], "lifecycle/microphone_denied.txt")
        device.shell("pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        for phase in ("permission_seed", "permission_revoked"):
            contract = lifecycle["phases"][phase]
            report["lifecycle"]["phases"][phase], _ = instrument_phase(
                contract, f"lifecycle/{phase}.txt", boundary=contract.get("interrupt"))
        device.shell("pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        report["lifecycle"]["phases"]["permission_regranted"], _ = instrument_phase(
            lifecycle["phases"]["permission_regranted"], "lifecycle/permission_regranted.txt")
        for phase, permission_action in (("notification_denied", "revoke"), ("notification_regranted", "grant")):
            if profile["api"] >= 33:
                device.shell("pm", permission_action, PACKAGE, "android.permission.POST_NOTIFICATIONS")
            report["lifecycle"]["phases"][phase], _ = instrument_phase(lifecycle["phases"][phase], f"lifecycle/{phase}.txt")
        report["lifecycle"]["passed"] = True
        # Run independent recovery/permission phases before layout, so a layout
        # regression cannot conceal their diagnostic outcomes. All gates remain required.
        report["layout"] = {"passed": False}
        if profile["screen_profile"] == "foldable":
            device.run("emu", "unfold")
            time.sleep(1)
            device.snapshot("layout-unfolded-baseline")
        report["layout"]["instrumentation"], report["layout"]["fold_events"] = instrument_phase(
            layout, "layout/instrumentation.txt", foldable=profile["screen_profile"] == "foldable", timeout=300)
        if profile["screen_profile"] == "foldable" and report["layout"]["fold_events"] != [{"posture": "fold"}, {"posture": "unfold"}]:
            raise RuntimeError("Foldable profile did not exercise fold and unfold")
        report["layout"]["passed"] = True
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
    run.add_argument("--previous-apk", required=True, help="Previous published signed release APK for a real update")
    run.add_argument("--previous-metadata", required=True, help="SHA-bound previous-release provenance JSON")
    run.add_argument("--profile", required=True, help="Required profile ID from profiles.json")
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
