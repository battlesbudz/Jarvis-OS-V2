#!/usr/bin/env python3
"""Bounded API 29 ARM64 software boot, then run the unchanged release controller.

SDK packages/AVD settings follow android-emulator-runner at a421e43855164a8197daf9d8d40fe71c6996bb0d:
https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/sdk-installer.ts
https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/emulator-manager.ts
Unlike that action, boot completion alone never authorizes an input command.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import selectors
import shutil
import signal
import stat
import subprocess
import struct
import sys
import tempfile
import time
from xml.dom import minidom
from xml.etree import ElementTree
import zipfile
import zlib

try:
    from .profiles import load_profiles
except ImportError:
    from profiles import load_profiles

SERVICES = ("input", "activity", "package", "window")
AVD_NAME = "jarvis-api29-software"
SERIAL = "emulator-5554"
# Official manual-install package; this controlled API 29 diagnostic does not
# establish that this Stable version repairs framework startup/install failures.
# https://developer.android.com/studio/emulator_archive
EMULATOR_PIN = {
    "version": "32.1.15", "build_id": "10696886", "channel": "Stable",
    "url": "https://dl.google.com/android/repository/emulator-darwin_aarch64-10696886.zip",
    "size_bytes": 265751100,
    "sha256": "f70d764fd756664bc782bb24f8da67cbaa51d7e5ffac732108b9e6545cd9faf4",
}

# Build 963 initialized GLES 3 but failed API 29 boot. This supported 140dpi
# raster keeps the exact physical dp extent of 720x1280@280 with one-quarter
# of its pixels. Reduced pixel count is an experiment, not a measured cause or
# cure for the permission-initialization timeout; all release gates remain required.
# https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/vl.c
# Return to the most stable tested CPU baseline; one and three guest CPUs
# both incurred system-server restarts and did not complete the boot barrier.
SOFTWARE_CPU_COUNT = 2
# Jarvis has no SensorManager consumers. Its layout gate forces real display
# rotation through UIAutomator, independently of sensor-based auto-rotation.
# Build 1025's sensor HAL still consumed 19-22% of a guest CPU after motion
# sensors were disabled. Use Android's supported empty-sensor configuration
# for this software-only fixture, retaining microphone/audio and other profiles.
# This is a load-reduction trial; the unchanged full gates establish usability.
# https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/hw-sensors.cpp
# https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android10-release/services/sensorservice/SensorService.cpp
SOFTWARE_DISABLED_SENSORS = (
    "hw.accelerometer", "hw.accelerometer_uncalibrated", "hw.gyroscope",
    "hw.sensors.gyroscope_uncalibrated", "hw.sensors.orientation",
    "hw.sensors.light", "hw.sensors.proximity", "hw.sensors.magnetic_field",
    "hw.sensors.magnetic_field_uncalibrated", "hw.sensors.pressure",
    "hw.sensors.humidity", "hw.sensors.temperature",
)
# Halve the software fixture's requested display cadence after Build 1042's
# saturated guest CPU and prolonged UI dispatch. Emulator 32 passes this to
# both its host VsyncThread and the API 29 composer's qemu.vsync property.
# This is a load-reduction trial, not proof of improved boot/runtime health;
# retained native and DisplayDeviceInfo logs establish the actual cadence.
# https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/userspace-boot-properties.cpp#325
SOFTWARE_AVD_SETTINGS = {"hw.cpu.ncore": str(SOFTWARE_CPU_COUNT), "hw.ramSize": "2048M", "vm.heapSize": "256M",
                         "hw.lcd.width": "360", "hw.lcd.height": "640", "hw.lcd.density": "140",
                         "hw.lcd.vsync": "30",
                         "disk.dataPartition.size": "4096M",
                         **{sensor: "no" for sensor in SOFTWARE_DISABLED_SENSORS}}
# Retain Build 963's requested selector. Its actual guest backend was
# ANGLE/Vulkan SwiftShader with GLES 3, not a proven direct-only path.
# Requested selector and fresh actual backend remain separate receipts;
# no performance improvement or complete device pass has been established.
# https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/opengl/emugl_config.cpp
SOFTWARE_GPU_SELECTOR = "swiftshader_indirect"
# Emulator 32 disables HostComposition by default below API 32 (b/243189303),
# despite this API 29 image advertising support. Build 986 repeatedly crashed
# its guest composer in GoldfishGralloc::getHostHandle without that extension.
# Keep the guest-supported host-composition path explicit; readiness and the
# unchanged full controller, not this request, establish a usable device.
# https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/main-emugl.cpp
SOFTWARE_ENABLED_FEATURES = ("HostComposition",)
SOFTWARE_DISABLED_FEATURES = ("HVF", "Vulkan")


def require_software_profile(profile, system=None, machine=None):
    if (system or platform.system(), machine or platform.machine()) != ("Darwin", "arm64"):
        raise RuntimeError("API 29 software emulation requires a native Darwin/arm64 host")
    if (profile["api"], profile["arch"], profile["target"], profile["acceleration"], profile["runner"]) != (
            29, "arm64-v8a", "default", "software", "macos-15"):
        raise RuntimeError("Software launcher requires the genuine API 29 ARM64 profile")


def remaining(deadline, now=time.monotonic):
    seconds = deadline - now()
    if seconds <= 0:
        raise TimeoutError("Declared emulator deadline expired")
    return seconds


def capture_bounded_output(command, path, *, environment, deadline, max_bytes, now=time.monotonic):
    """Stream a diagnostic's combined output; never buffer or duplicate its payload.

    Killing/reaping only this client avoids waiting on inherited pipes or touching
    the emulator's process group. Capture limits cannot authorize readiness.
    """
    receipt = {"command": command, "file": path.name, "status": "error", "bytes": 0,
               "max_bytes": max_bytes, "exit_code": None, "deadline_monotonic_seconds": deadline}
    process = None
    started = now()
    try:
        with path.open("wb") as output:
            remaining(deadline, now)
            process = subprocess.Popen(command, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
            os.set_blocking(process.stdout.fileno(), False)
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    events = selector.select(remaining(deadline, now))
                    remaining(deadline, now)
                    if not events:
                        continue
                    # One extra byte distinguishes a full file from truncated output.
                    space = max_bytes - receipt["bytes"]
                    try:
                        chunk = os.read(process.stdout.fileno(), min(65536, space + 1))
                    except BlockingIOError:
                        continue
                    if not chunk:
                        process.wait(timeout=remaining(deadline, now))
                        receipt["status"] = "completed" if process.returncode == 0 else "nonzero_exit"
                        break
                    output.write(chunk[:space])
                    receipt["bytes"] += min(len(chunk), space)
                    if len(chunk) > space:
                        receipt["status"] = "truncated"
                        break
    except (TimeoutError, subprocess.TimeoutExpired):
        receipt["status"] = "timeout" if process is not None else "skipped_deadline"
    except (OSError, ValueError) as error:
        receipt["error"] = str(error)
    finally:
        if process is not None:
            try:
                if process.poll() is None:
                    try:
                        process.kill()
                    except ProcessLookupError:
                        pass
                # SIGKILL then reap, without communicate() draining inherited pipes
                # or granting a fresh timeout after the collection deadline.
                process.wait()
                receipt["exit_code"] = process.returncode
            finally:
                if process.stdout is not None:
                    process.stdout.close()
        receipt["seconds"] = round(now() - started, 3)
    return receipt


def extract_emulator(archive, destination, deadline, now):
    """Extract only regular files/directories under emulator/, retaining modes."""
    try:
        with zipfile.ZipFile(archive) as zipped:
            members, names, total = zipped.infolist(), set(), 0
            if not members or len(members) > 4096:
                raise ValueError("Unexpected emulator archive entry count")
            for member in members:
                remaining(deadline, now)
                name = member.filename
                path = PurePosixPath(name)
                mode = member.external_attr >> 16
                kind = stat.S_IFMT(mode)
                canonical = path.as_posix() + ("/" if member.is_dir() else "")
                if (not name or not path.parts or name != canonical or "\\" in name or "\0" in name or path.is_absolute()
                        or ".." in path.parts or path.parts[0] != "emulator"
                        or name.rstrip("/").casefold() in names or member.flag_bits & 1
                        or kind != (stat.S_IFDIR if member.is_dir() else stat.S_IFREG)
                        or mode & 0o7000):
                    raise ValueError(f"Unsafe emulator archive entry: {name!r}")
                names.add(name.rstrip("/").casefold())
                total += member.file_size
            if total > 2 * 1024 ** 3:
                raise ValueError("Unexpected emulator archive expanded size")
            directories = []
            for member in members:
                remaining(deadline, now)
                target = destination / member.filename
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    directories.append((target, member.external_attr >> 16 & 0o777))
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(member) as source, target.open("xb") as output:
                    while chunk := source.read(1024 * 1024):
                        remaining(deadline, now)
                        output.write(chunk)
                target.chmod(member.external_attr >> 16 & 0o777)
            for target, mode in reversed(directories):
                remaining(deadline, now)
                target.chmod(mode)
    except zipfile.BadZipFile as error:
        raise ValueError(f"Invalid emulator archive: {error}") from error


def emulator_properties(directory):
    source = directory / "source.properties"
    if source.is_symlink() or not source.is_file() or source.stat().st_size > 65536:
        raise ValueError("Invalid emulator source.properties")
    properties = {}
    for line in source.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in properties:
            raise ValueError("Malformed emulator source.properties")
        properties[key.strip()] = value.strip()
    expected = {"Pkg.Revision": EMULATOR_PIN["version"], "Pkg.BuildId": EMULATOR_PIN["build_id"],
                "Pkg.Path": "emulator"}
    if any(properties.get(key) != value for key, value in expected.items()):
        raise ValueError(f"Unexpected emulator source.properties: {properties}")
    return properties


def pinned_package_xml(original, staged):
    # The official manual install requires preserving package.xml and updating
    # its revision. minidom also preserves prefixes used only in xsi:type values.
    source = original / "package.xml"
    if source.is_symlink() or not source.is_file() or source.stat().st_size > 1024 * 1024:
        raise ValueError("Existing SDK emulator package.xml is required")
    raw = source.read_bytes()
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise ValueError("Unsupported SDK package.xml declarations")
    try:
        document = minidom.parseString(raw)
        packages = [node for node in document.getElementsByTagName("*")
                    if node.localName == "localPackage" and node.getAttribute("path") == "emulator"]
        if len(packages) != 1:
            raise ValueError("Expected one emulator localPackage")

        def children(node, name):
            return [child for child in node.childNodes if child.nodeType == child.ELEMENT_NODE
                    and child.localName == name]

        revisions = children(packages[0], "revision")
        if len(revisions) != 1:
            raise ValueError("Expected one SDK emulator revision")
        revision = revisions[0]
        for name, value in zip(("major", "minor", "micro"), EMULATOR_PIN["version"].split(".")):
            values = children(revision, name)
            if len(values) != 1:
                raise ValueError(f"Expected one SDK emulator revision {name}")
            node = values[0]
            for child in list(node.childNodes):
                node.removeChild(child)
            node.appendChild(document.createTextNode(value))
        for preview in children(revision, "preview"):
            revision.removeChild(preview)
        target = staged / "package.xml"
        target.write_bytes(document.toxml(encoding="utf-8"))
        target.chmod(stat.S_IMODE(source.stat().st_mode) & 0o777)
    except Exception as error:
        raise ValueError(f"Invalid SDK emulator package.xml: {error}") from error


def require_pinned_version(result):
    raw = result.stdout + result.stderr
    versions = re.findall(r"^Android emulator version (\d+\.\d+\.\d+)(?:\.0)? \(build_id (\d+)\).*$",
                          raw, re.MULTILINE)
    if versions != [(EMULATOR_PIN["version"], EMULATOR_PIN["build_id"])]:
        raise ValueError(f"Unexpected actual emulator version: {raw.strip()}")
    return raw


def wait_for_android(adb, running, deadline, *, now=time.monotonic, pause=time.sleep, record=lambda state: None):
    """Require the boot flag and every binder service, within one shared deadline."""
    state = {"boot_completed": False, "services": {name: False for name in SERVICES}}
    while now() < deadline:
        if not running():
            raise RuntimeError("Emulator exited before Android became ready")
        boot = adb("shell", "getprop", "sys.boot_completed", deadline=deadline)
        state = {"boot_completed": boot.returncode == 0 and boot.stdout.strip() == "1",
                 "services": {name: False for name in SERVICES}}
        if state["boot_completed"]:
            for name in SERVICES:
                result = adb("shell", "service", "check", name, deadline=deadline)
                state["services"][name] = (result.returncode == 0 and
                                            result.stdout.strip() == f"Service {name}: found")
        record(state)
        if state["boot_completed"] and all(state["services"].values()) and running() and now() < deadline:
            return state
        pause(min(2, max(0, deadline - now())))
    raise TimeoutError(f"Android boot/services not ready within declared timeout: {json.dumps(state)}")


def emulator_command(sdk, diagnostics):
    # -logcat-output captures guest logs from startup, including when adb is broken.
    # AOSP f0c183f1, android-qemu2-glue/main.cpp and android/android-emu/android/main-common.c.
    # Emulator 32 forwards the AVD core count only with acceleration; explicit
    # QEMU SMP retains the declared CPUs for TCG. Actual kernel proof is required.
    # -verbose retains older OpenGL identity and final QEMU argv diagnostics.
    # https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android-qemu2-glue/main.cpp
    return [str(sdk / "emulator/emulator"), "-port", "5554", "-avd", AVD_NAME,
            "-no-window", "-gpu", SOFTWARE_GPU_SELECTOR, "-noaudio", "-no-boot-anim", "-no-snapshot",
            "-timezone", "Etc/UTC",
            "-accel", "off", "-feature", ",".join((*SOFTWARE_ENABLED_FEATURES,
                *("-" + feature for feature in SOFTWARE_DISABLED_FEATURES))),
            "-verbose", "-show-kernel", "-logcat", "*:V",
            "-logcat-output", str(diagnostics / "guest-startup-logcat.txt"), "-qemu", "-smp", str(SOFTWARE_CPU_COUNT)]


def keyguard_dismissed(output):
    """Read only Android 10's actual KeyguardController dump, never unrelated flags.

    https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/KeyguardController.java
    """
    lines = output.splitlines()
    # Android 10 ActivityStackSupervisor.dump prints the preceding boolean with
    # pw.print, so the controller header may share that line (no intervening LF).
    header = re.compile(r"^[ \t]*(?:isHomeRecentsComponent=(?:true|false)[ \t]+)?KeyguardController:$")
    headers = [index for index, line in enumerate(lines) if header.fullmatch(line)]
    if len(headers) != 1:
        return False
    index = headers[0]
    indent = len(lines[index]) - len(lines[index].lstrip())
    block = []
    for line in lines[index + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        block.append(line)
    return all(re.findall(rf"^\s*{name}=(true|false)\s*$", "\n".join(block), re.MULTILINE) == ["false"]
               for name in ("mKeyguardShowing", "mAodShowing", "mKeyguardGoingAway"))


def wait_for_unlock(adb, running, deadline, *, now=time.monotonic, pause=time.sleep, record=lambda raw: None):
    while now() < deadline:
        if not running():
            raise RuntimeError("Emulator exited before unlock was observed")
        result = adb("shell", "dumpsys", "activity", "activities", deadline=deadline, timeout=30)
        record(result.stdout + result.stderr)
        if result.returncode == 0 and keyguard_dismissed(result.stdout) and running() and now() < deadline:
            return
        pause(min(2, max(0, deadline - now())))
    raise TimeoutError("Actual keyguard dismissal was not observed within declared boot timeout")


def read_native_boot_log(path, *, deadline, now=time.monotonic, max_bytes=256 * 1024):
    """Read complete lines from a bounded tail of this launch's native guest log.

    Build 944's filtered adb logcat dumps all timed out, although the independent
    native stream retained the actual completion marker. Only a live observation
    inside the original deadline can authorize readiness; retained artifacts cannot.
    """
    path = Path(path)
    receipt = {"source": "native-startup-logcat", "path": path.name,
               "max_bytes": max_bytes, "exit_code": 1, "stdout": "", "stderr": ""}
    try:
        remaining(deadline, now)
        if path.is_symlink() or not path.is_file():
            raise ValueError("Native startup log must be a regular file")
        with path.open("rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):
                raise ValueError("Native startup log must be a regular file")
            start = max(0, before.st_size - max_bytes)
            stream.seek(start)
            raw = stream.read(before.st_size - start)
            after, current = os.fstat(stream.fileno()), path.stat()
            receipt.update(file_size_bytes=before.st_size, file_identity=[before.st_dev, before.st_ino],
                           start_offset=start, read_bytes=len(raw))
            if (len(raw) != before.st_size - start or after.st_size < before.st_size
                    or current.st_size < before.st_size
                    or (after.st_dev, after.st_ino) != (current.st_dev, current.st_ino)):
                raise ValueError("Native startup log was truncated or replaced during observation")
        # A tail can begin in the middle of a line, and a concurrent writer can
        # leave its last line unfinished. Neither fragment is completion evidence.
        first = raw.find(b"\n") + 1 if start else 0
        last = raw.rfind(b"\n") + 1
        complete = raw[first:last] if last >= first else b""
        receipt.update(complete_start_offset=start + first, complete_end_offset=start + last,
                       incomplete_suffix_bytes=len(raw) - last, stdout=complete.decode("utf-8"))
        remaining(deadline, now)
        receipt["exit_code"] = 0
    except (OSError, ValueError, UnicodeError, TimeoutError) as error:
        receipt["exit_code"] = 124 if isinstance(error, TimeoutError) else 1
        receipt["stderr"] = str(error)
    receipt["observed_monotonic_seconds"] = now()
    receipt["deadline_monotonic_seconds"] = deadline
    return receipt


def wait_for_boot_broadcast(adb, running, deadline, *, now=time.monotonic, pause=time.sleep,
                            record=lambda raw: None, log_reader=None):
    """Observe Android 10's ordered user0 boot delivery, within the existing budget.

    Build 937 started installation while cold-boot receivers still consumed guest CPU.
    This completion barrier tests that contention hypothesis; it does not prove idle
    CPU or a successful installation. Android 10 emits this exact completion message:
    https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/am/UserController.java
    """
    state = {"completed": False}

    def expired():
        summary = {key: state[key] for key in ("completed", "system_server_pid") if key in state}
        summary["probe_exit_codes"] = {key: probe["exit_code"]
                                       for key, probe in state.get("probes", {}).items()}
        raise TimeoutError("Actual user0 BOOT_COMPLETED delivery was not observed within declared boot timeout: "
                           + json.dumps(summary))

    while now() < deadline:
        if not running():
            raise RuntimeError("Emulator exited before user0 BOOT_COMPLETED delivery was observed")
        state = {"completed": False, "probes": {}}
        if log_reader is not None:
            if now() >= deadline:
                expired()
            # Scan the bounded local tail before spending guest CPU on PID
            # probes. This candidate is only a hint: the full PID/log/PID
            # observation below must read the log again and validate readiness.
            candidate = state["probes"]["log_candidate"] = log_reader(deadline=deadline)
            pattern = (r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+[1-9][0-9]*\s+"
                       r"[0-9]+\s+I\s+ActivityManager\s*:\s+"
                       r"Finished processing BOOT_COMPLETED for u0\s*$")
            record(state)
            if candidate["exit_code"] != 0 or not any(
                    re.fullmatch(pattern, line) for line in candidate["stdout"].split("\n")):
                pause(min(2, max(0, deadline - now())))
                continue
        for name, args in (("pid_before", ("shell", "pidof", "system_server")),
                           ("logcat", ("logcat", "-b", "system", "-d", "-v", "threadtime",
                                       "-s", "ActivityManager:I")),
                           ("pid_after", ("shell", "pidof", "system_server"))):
            if now() >= deadline:
                expired()
            if name == "logcat" and log_reader is not None:
                state["probes"][name] = log_reader(deadline=deadline)
            else:
                result = adb(*args, deadline=deadline, timeout=15)
                state["probes"][name] = {"exit_code": result.returncode, "stdout": result.stdout,
                                         "stderr": result.stderr}
            record(state)
        probes = state["probes"]
        before = re.fullmatch(r"\s*([1-9][0-9]*)\s*", probes["pid_before"]["stdout"])
        after = re.fullmatch(r"\s*([1-9][0-9]*)\s*", probes["pid_after"]["stdout"])
        if (all(probe["exit_code"] == 0 for probe in probes.values()) and before and after
                and before.group(1) == after.group(1)):
            pid = before.group(1)
            state["system_server_pid"] = int(pid)
            # The fresh guest's current PID must remain stable across the filtered
            # receipt. A previous crashed system_server's marker cannot authorize it.
            pattern = (rf"^\d{{2}}-\d{{2}} \d{{2}}:\d{{2}}:\d{{2}}\.\d{{3}}\s+{pid}\s+"
                       r"[0-9]+\s+I\s+ActivityManager\s*:\s+"
                       r"Finished processing BOOT_COMPLETED for u0\s*$")
            if log_reader is not None:
                # Match one complete native record, never whitespace spanning
                # separate lines, both here and in the candidate scan above.
                state["completed"] = any(re.fullmatch(pattern, line)
                                         for line in probes["logcat"]["stdout"].split("\n"))
            else:
                state["completed"] = bool(re.search(pattern, probes["logcat"]["stdout"], re.MULTILINE))
        record(state)
        if state["completed"] and running() and now() < deadline:
            return state
        pause(min(2, max(0, deadline - now())))
    expired()


# Android 10 gives error windows a system-owned window and a PRIVATE_FLAG_SYSTEM_ERROR.
# Match both WindowManager ownership and fresh accessibility content, never text alone.
# https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/am/AppNotRespondingDialog.java
# https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/WindowState.java
SYSTEM_UI_ANR_WINDOW = "Application Not Responding: com.android.systemui"
SYSTEM_UI_ANR_TITLE = "System UI isn't responding"


def startup_error_window(raw, *, settling_window=None):
    """Return the sole stock System UI error window, or None for a clean window dump."""
    # Default dumpsys includes a saved LAST ANR snapshot with obsolete windows,
    # focus and configuration. Android 10 prints the live POLICY section next.
    # https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/WindowManagerService.java
    policy = "WINDOW MANAGER POLICY STATE (dumpsys window policy)"
    if raw.count(policy) != 1:
        raise ValueError("Missing or ambiguous live WindowManager sections")
    raw = raw.split(policy, 1)[1]
    if (raw.count("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)") != 1
            or raw.count("WINDOW MANAGER WINDOWS (dumpsys window windows)") != 1
            or len(re.findall(r"^  mGlobalConfiguration=.+$", raw, re.MULTILINE)) != 1):
        raise ValueError("Incomplete startup WindowManager dump")
    headers = list(re.finditer(r"^  Window #[0-9]+ (Window\{[0-9a-f]+ u[0-9]+ ([^}\n]+)\}):$",
                               raw, re.MULTILINE))
    focus = re.findall(r"^\s*mCurrentFocus=(Window\{[^}\n]+\})\s*$", raw, re.MULTILINE)
    if not headers or len(focus) != 1 or focus[0] not in [match[1] for match in headers]:
        raise ValueError("Missing or ambiguous focused startup window")
    errors = []
    for index, match in enumerate(headers):
        end = headers[index + 1].start() if index + 1 < len(headers) else raw.index("  mGlobalConfiguration=")
        block = raw[match.end():end]
        title = match[2]
        if (title.startswith(("Application Not Responding:", "Application Error:", "Error Dialog"))
                or re.search(r"\b(?:SYSTEM_ERROR|SYSTEM_ALERT)\b", block)):
            errors.append((match[1], title, block))
    if not errors:
        return None
    if len(errors) != 1:
        raise ValueError("Multiple startup error windows; recovery is not permitted")
    window, title, block = errors[0]
    identity = window[:-len(" EXITING}")] + "}" if window.endswith(" EXITING}") else window
    settling = settling_window is not None and identity == settling_window
    allowed_visibility = (["true"], ["false"]) if settling else (["true"],)
    if (title != SYSTEM_UI_ANR_WINDOW and not (settling and title == SYSTEM_UI_ANR_WINDOW + " EXITING")
            or (not settling and focus != [window])
            or re.findall(r"\bmOwnerUid=(\d+)\b", block) != ["1000"]
            or re.findall(r"\bpackage=([^\s]+)", block) != ["android"]
            or re.findall(r"\bmDisplayId=(\d+)\b", block) != ["0"]
            or any(re.findall(rf"^[ \t]*{flag}=(true|false)[ \t]*$", block, re.MULTILINE)
                   not in allowed_visibility for flag in ("isOnScreen", "isVisible"))
            or not re.search(r"\bty=(?:SYSTEM_ALERT|SYSTEM_ERROR)\b", block)
            or not re.search(r"\bpfl=[^\r\n}]*\bSYSTEM_ERROR\b", block)):
        raise ValueError("Unrecognized or unowned startup error window; recovery is not permitted")
    if settling_window is not None and not settling:
        raise RuntimeError("New or repeated System UI ANR dialog after Wait")
    return settling_window if settling else window


def startup_wait_target(raw, window, display):
    """Authorize only the exact stock title and one enabled Android Wait button."""
    if len(raw) > 1024 * 1024 or "<!DOCTYPE" in raw.upper() or "<!ENTITY" in raw.upper():
        raise ValueError("Unsupported startup UI hierarchy")
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as error:
        raise ValueError("Malformed startup UI hierarchy") from error
    nodes = list(root.iter("node"))
    if root.tag != "hierarchy" or root.get("rotation") != "0" or not nodes:
        raise ValueError("Missing or unexpected startup UI hierarchy")
    alerts = [node for node in nodes if node.get("resource-id") == "android:id/alertTitle"]
    actions = [node for node in nodes if node.get("resource-id", "").startswith("android:id/aerr_")]
    if window is None:
        if alerts or actions or any("isn't responding" in node.get("text", "") for node in nodes):
            raise ValueError("Unrecognized startup dialog in UI hierarchy")
        return None
    titles = [node for node in alerts if node.get("text") == SYSTEM_UI_ANR_TITLE]
    waits = [node for node in actions if node.get("resource-id") == "android:id/aerr_wait"]
    if (len(alerts) != 1 or len(titles) != 1 or len(waits) != 1
            or len([node for node in actions if node.get("resource-id") == "android:id/aerr_close"]) != 1
            or any(node.get("package") != "android" for node in nodes)
            or any(node.get("resource-id") not in ("android:id/aerr_close", "android:id/aerr_wait")
                   for node in actions)):
        raise ValueError("Ambiguous or non-stock System UI ANR hierarchy")
    target = waits[0]
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", target.get("bounds", ""))
    if (target.get("text") != "Wait" or target.get("class") != "android.widget.Button"
            or target.get("enabled") != "true" or target.get("clickable") != "true"
            or target.get("visible-to-user", "true") != "true" or not bounds):
        raise ValueError("System UI Wait target is not safely actionable")
    x1, y1, x2, y2 = map(int, bounds.groups())
    if not (0 <= x1 < x2 <= display["width"] and 0 <= y1 < y2 <= display["height"]):
        raise ValueError("System UI Wait bounds are outside the verified display")
    return [(x1 + x2) // 2, (y1 + y2) // 2]


def require_startup_png(path, display):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Missing or oversized startup screenshot")
    raw = path.read_bytes()
    offset, kinds = 8, []
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid startup screenshot signature")
    while offset < len(raw):
        if len(raw) - offset < 12:
            raise ValueError("Truncated startup screenshot")
        size = int.from_bytes(raw[offset:offset + 4], "big")
        kind, end = raw[offset + 4:offset + 8], offset + size + 12
        if (end > len(raw) or zlib.crc32(raw[offset + 4:end - 4]) != int.from_bytes(raw[end - 4:end], "big")
                or (not kinds and (kind != b"IHDR" or size != 13
                    or struct.unpack(">II", raw[offset + 8:offset + 16]) != (display["width"], display["height"])))
                or (kind == b"IEND" and (size != 0 or end != len(raw)))):
            raise ValueError("Invalid startup screenshot chunk")
        kinds.append(kind)
        offset = end
    if not kinds or kinds[-1] != b"IEND" or kinds.count(b"IHDR") != 1 or b"IDAT" not in kinds:
        raise ValueError("Incomplete startup screenshot")


def startup_anr_history(raw, pid):
    """Use stream order: the guest clock can jump during a cold boot."""
    prefix = rf"^\d{{2}}-\d{{2}} \d{{2}}:\d{{2}}:\d{{2}}\.\d{{3}}[ \t]+{pid}[ \t]+\d+[ \t]+"
    boot = list(re.finditer(prefix + r"I[ \t]+ActivityManager[ \t]*:[ \t]+Finished processing BOOT_COMPLETED for u0[ \t]*$",
                            raw, re.MULTILINE))
    anrs = list(re.finditer(prefix + r"E[ \t]+ActivityManager[ \t]*:[ \t]+ANR in ([^\s]+)(?:[ \t]+[^\r\n]*)?$",
                            raw, re.MULTILINE))
    if len(boot) != 1:
        raise ValueError("Ambiguous current-server BOOT_COMPLETED history")
    if any(match.start() > boot[0].start() for match in anrs):
        raise RuntimeError("New ANR after BOOT_COMPLETED; startup recovery is not permitted")
    return {"boot_offset": boot[0].start(),
            "cold_system_ui_anrs": [match.start() for match in anrs if match[1] == "com.android.systemui"]}


class SoftwareSession:
    def __init__(self, profile, out, sdk, *, now=time.monotonic):
        self.profile, self.out, self.sdk, self.now = profile, Path(out), Path(sdk), now
        self.diagnostics = self.out.with_name(self.out.name + "-software-startup")
        self.diagnostics.mkdir(parents=True, exist_ok=False)
        self.deadline = now() + profile["job_timeout"] * 60
        self.environment = dict(os.environ)
        # This directory contains only this launcher's disposable device data.
        self.avd_home = self.diagnostics.with_name(self.diagnostics.name + "-avd").resolve()
        self.avd_home.mkdir()
        self.environment["ANDROID_AVD_HOME"] = str(self.avd_home)
        self.environment["ANDROID_SERIAL"] = SERIAL
        self.environment["PATH"] = os.pathsep.join((str(self.sdk / "platform-tools"),
                                                   str(self.sdk / "cmdline-tools/latest/bin"),
                                                   self.environment.get("PATH", "")))
        self.emulator = None
        self.boot_failed = False
        self.report = {"schema": 1, "passed": False, "profile": profile,
                       "acceleration": "software", "boot_timeout_seconds": profile["boot_timeout"],
                       "status": "provisioning", "errors": [],
                       "graphics": {"requested_selector": SOFTWARE_GPU_SELECTOR,
                                    "requested_enabled_features": list(SOFTWARE_ENABLED_FEATURES),
                                    "requested_disabled_features": list(SOFTWARE_DISABLED_FEATURES)}}

    def run(self, command, *, deadline=None, timeout=60, check=True, input=None):
        deadline = self.deadline if deadline is None else min(deadline, self.deadline)
        limit = min(timeout, remaining(deadline, self.now))
        started = self.now()
        try:
            result = subprocess.run(command, input=input, env=self.environment, text=True,
                                    capture_output=True, timeout=limit)
        except subprocess.TimeoutExpired as error:
            def decoded(value):
                return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
            result = subprocess.CompletedProcess(command, 124, decoded(error.stdout),
                                                 decoded(error.stderr) + "\nCommand deadline expired")
        with (self.diagnostics / "commands.jsonl").open("a") as stream:
            stream.write(json.dumps({"command": command, "exit_code": result.returncode,
                                     "seconds": round(self.now() - started, 3), "stdout": result.stdout,
                                     "stderr": result.stderr}) + "\n")
        if check and result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(command)}\n{result.stderr}")
        return result

    def adb(self, *args, deadline, check=False, timeout=15):
        return self.run([str(self.sdk / "platform-tools/adb"), "-s", SERIAL, *args],
                        deadline=deadline, timeout=timeout, check=check)

    def pin_emulator(self):
        require_software_profile(self.profile)
        deadline = min(self.deadline, self.now() + 600)
        proof = self.report["emulator_pin"] = dict(EMULATOR_PIN, verified=False,
                                                 timeout_seconds=600)
        original = self.sdk / "emulator"
        if original.is_symlink() or not original.is_dir():
            raise ValueError("A regular SDK emulator directory is required")
        # Same-filesystem staging lets publication use rename only after every
        # archive/metadata/executable check succeeds. Never reuse partial input.
        with tempfile.TemporaryDirectory(prefix="jarvis-emulator-", dir=self.sdk) as temporary:
            stage = Path(temporary)
            archive = stage / "emulator.zip"
            self.run(["curl", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                      "--connect-timeout", "30", "--max-time", str(remaining(deadline, self.now)),
                      "--max-filesize", str(EMULATOR_PIN["size_bytes"]),
                      "--output", str(archive), EMULATOR_PIN["url"]], deadline=deadline, timeout=600)
            remaining(deadline, self.now)
            proof["actual_size_bytes"] = archive.stat().st_size
            if proof["actual_size_bytes"] != EMULATOR_PIN["size_bytes"]:
                raise ValueError("Emulator archive size differs from official pinned package")
            digest = hashlib.sha256()
            with archive.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    remaining(deadline, self.now)
                    digest.update(chunk)
            remaining(deadline, self.now)
            proof["actual_sha256"] = digest.hexdigest()
            if proof["actual_sha256"] != EMULATOR_PIN["sha256"]:
                raise ValueError("Emulator archive SHA256 differs from official pinned package")
            proof["archive_verified"] = True
            unpacked = stage / "unpacked"
            unpacked.mkdir()
            extract_emulator(archive, unpacked, deadline, self.now)
            candidate = unpacked / "emulator"
            proof["source_properties"] = emulator_properties(candidate)
            pinned_package_xml(original, candidate)
            remaining(deadline, self.now)
            self.run(["xattr", "-dr", "com.apple.quarantine", str(candidate)], deadline=deadline)
            proof["staged_version_output"] = require_pinned_version(
                self.run([str(candidate / "emulator"), "-version"], deadline=deadline))
            shutil.copyfile(candidate / "source.properties", self.diagnostics / "emulator-source.properties")
            shutil.copyfile(candidate / "package.xml", self.diagnostics / "emulator-package.xml")
            remaining(deadline, self.now)
            backup = stage / "original-emulator"
            original.rename(backup)
            try:
                candidate.rename(original)
                proof["installed_version_output"] = require_pinned_version(
                    self.run([str(original / "emulator"), "-version"], deadline=deadline))
                # Native help is a receipt, not an exhaustive parser capability
                # contract. Actual startup/backend selection and readiness decide
                # whether this pinned binary can run the documented selector.
                help_result = self.run([str(original / "emulator"), "-help-gpu"],
                                       deadline=deadline, timeout=15, check=False)
                self.report["graphics"]["native_help"] = {
                    "exit_code": help_result.returncode, "stdout": help_result.stdout,
                    "stderr": help_result.stderr,
                    "mentions_software": bool(re.search(r"\bsoftware\b", help_result.stdout))}
                remaining(deadline, self.now)
                feature_help = self.run([str(original / "emulator"), "-help-feature"],
                                        deadline=deadline, timeout=15, check=False)
                self.report["graphics"]["native_feature_help"] = {
                    "exit_code": feature_help.returncode, "stdout": feature_help.stdout,
                    "stderr": feature_help.stderr}
                remaining(deadline, self.now)
            except (OSError, ValueError, RuntimeError, TimeoutError):
                if original.exists():
                    shutil.rmtree(original)
                backup.rename(original)
                raise
            proof["verified"] = True

    def provision(self):
        require_software_profile(self.profile)
        sdkmanager = str(self.sdk / "cmdline-tools/latest/bin/sdkmanager")
        self.run([sdkmanager, "--licenses"], timeout=120, input="y\n" * 100)
        self.run([sdkmanager, "--install", "build-tools;37.0.0", "platform-tools", "platforms;android-29"], timeout=600)
        self.run([sdkmanager, "--install", "emulator", "--channel=0"], timeout=600)
        image = "system-images;android-29;default;arm64-v8a"
        self.run([sdkmanager, "--install", image, "--channel=0"], timeout=600)
        self.pin_emulator()
        avd_home = Path(self.environment["ANDROID_AVD_HOME"])
        avd_home.mkdir(parents=True, exist_ok=True)
        self.run([str(self.sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "--force",
                  "-n", AVD_NAME, "--package", image, "--device", self.profile["device_profile"]],
                 input="no\n", timeout=120)
        config = avd_home / f"{AVD_NAME}.avd/config.ini"
        retained = [line for line in config.read_text().splitlines()
                    if line.partition("=")[0].strip() not in SOFTWARE_AVD_SETTINGS]
        config.write_text("\n".join(retained) + "\n" +
                          "".join(f"{key}={value}\n" for key, value in SOFTWARE_AVD_SETTINGS.items()))
        self.report["avd_settings"] = SOFTWARE_AVD_SETTINGS
        shutil.copyfile(config, self.diagnostics / "avd-config.ini")
        self.run([str(self.sdk / "platform-tools/adb"), "start-server"])

    def capture_host_resources(self, stage, deadline):
        """Optional read-only receipts consume the existing budget, never extend it."""
        commands = (["/usr/sbin/sysctl", "-n", "hw.ncpu", "hw.memsize"],
                    ["/usr/bin/vm_stat"], ["/usr/bin/memory_pressure", "-Q"],
                    ["/bin/ps", "-p", str(self.emulator.pid), "-o", "pid=,pcpu=,rss=,comm="])
        for command in commands:
            receipt = {"stage": stage, "command": command}
            try:
                result = self.run(command, deadline=deadline, timeout=2, check=False)
                receipt.update(exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
            except (OSError, TimeoutError) as error:
                receipt["error"] = str(error)
            try:
                with (self.diagnostics / "host-resources.jsonl").open("a") as stream:
                    stream.write(json.dumps(receipt) + "\n")
            except OSError as error:
                self.report.setdefault("host_resource_errors", []).append(str(error))
                return
            if self.now() >= min(deadline, self.deadline):
                break

    def require_display(self, deadline):
        # Verify physical framebuffer/density, including any SDK skin override.
        # An Android wm override would change logical layout without cutting the raster.
        display = {}
        expected = {"size": "360x640", "density": "140"}
        for field, value in expected.items():
            result = self.adb("shell", "wm", field, deadline=deadline, check=True)
            display[field] = {"stdout": result.stdout, "stderr": result.stderr}
            (self.diagnostics / "display-state.json").write_text(json.dumps(display, indent=2) + "\n")
            if result.stdout.strip() != f"Physical {field}: {value}":
                raise RuntimeError(f"Software emulator physical {field} must be {value}: {result.stdout.strip()}")
        remaining(min(deadline, self.deadline), self.now)
        self.report["display"] = {"width": 360, "height": 640, "density_dpi": 140}

    def capture_graphics_backend(self):
        """Read bounded, already captured startup output; never start another probe."""
        graphics = self.report["graphics"]
        try:
            with (self.diagnostics / "emulator-stdout.txt").open("rb") as stream:
                raw = stream.read(256 * 1024)
        except OSError as error:
            graphics["receipt_error"] = str(error)
            return
        graphics["receipt_source"] = "emulator-stdout.txt"
        graphics["receipt_bytes"] = len(raw)
        text = raw.decode(errors="replace")
        # Only the native renderer initialization statements identify actual
        # backends. Kernel/guest mentions of software graphics do not qualify.
        patterns = {
            "graphics_backend": r"^INFO\s*\| Graphics backend: (.+)$",
            "selected_modes": r"^INFO\s*\| emuglConfig_init: vulkan_mode_selected:(\S+) gles_mode_selected:(\S+)\s*$",
            "adapter": r"^INFO\s*\| Graphics Adapter (?!Vendor )(.+)$",
            "api_version": r"^INFO\s*\| Graphics API Version (.+)$",
            "vulkan_device": r"^INFO\s*\| Selecting Vulkan device: (.+)$"}
        evidence = []
        for field, pattern in patterns.items():
            matches = list(re.finditer(pattern, text, re.MULTILINE))
            if matches:
                evidence.extend(match.group(0) for match in matches)
                if field == "selected_modes":
                    graphics["vulkan_mode"], graphics["gles_mode"] = matches[-1].groups()
                else:
                    graphics[field] = matches[-1].group(1)
        graphics["backend_observed"] = all(field in graphics for field in
                                            ("graphics_backend", "vulkan_mode", "gles_mode", "adapter"))
        graphics["backend_receipt"] = evidence

    def require_startup_ui(self, deadline, *, pause=time.sleep):
        """One cold System UI Wait at most, before the release controller installs anything."""
        if "startup_ui" in self.report:
            raise RuntimeError("Startup UI recovery cannot be run more than once")
        proof = self.report["startup_ui"] = {"verified": False, "wait_attempts": 0, "observations": 0}
        expected_pid = self.report["boot_broadcast"]["system_server_pid"]
        native_identity, native_size = None, 0

        def live():
            remaining(min(deadline, self.deadline), self.now)
            if self.emulator.poll() is not None:
                raise RuntimeError("Emulator exited during startup UI verification")

        def record(stage, **fields):
            receipt = dict(fields)
            receipt.update(stage=stage, observed_monotonic_seconds=self.now(),
                           deadline_monotonic_seconds=deadline)
            with (self.diagnostics / "startup-ui.jsonl").open("a") as stream:
                stream.write(json.dumps(receipt) + "\n")

        def probe(stage, *args, timeout=30):
            live()
            result = self.adb(*args, deadline=deadline, timeout=timeout)
            record(stage, exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
            live()  # Late success is evidence, never permission for the next action.
            if result.returncode:
                raise RuntimeError(f"Startup UI {stage} failed ({result.returncode})")
            return result.stdout

        def history(stage):
            nonlocal native_identity, native_size
            live()
            path = self.diagnostics / "guest-startup-logcat.txt"
            # A full, bounded scan is necessary: the one cold ANR precedes the
            # boot barrier by minutes and is outside the 256 KiB completion tail.
            receipt = read_native_boot_log(path, deadline=deadline, now=self.now, max_bytes=16 * 1024 * 1024)
            raw = receipt.pop("stdout")
            record(stage, **receipt)
            live()
            if receipt["exit_code"] or receipt.get("start_offset") != 0:
                raise RuntimeError("Incomplete native startup history; recovery is not permitted")
            identity = receipt["file_identity"]
            if native_identity is not None and (identity != native_identity or receipt["file_size_bytes"] < native_size):
                raise RuntimeError("Native startup history changed or was truncated")
            native_identity, native_size = identity, receipt["file_size_bytes"]
            result = startup_anr_history(raw, expected_pid)
            record(stage + "-anrs", **result)
            return result

        def current_server(stage):
            raw = probe(stage, "shell", "pidof", "system_server", timeout=15)
            if raw.strip() != str(expected_pid):
                raise RuntimeError("System server changed during startup UI verification")

        def snapshot(stage):
            live()
            number = proof["observations"] = proof["observations"] + 1
            stem = f"startup-ui-{number:02d}-{stage}"
            remote = f"/data/local/tmp/{stem}"
            # Unique paths prevent a zero-exit failed dump from reusing old XML/PNG.
            probe(stem + "-screencap", "shell", "screencap", "-p", remote + ".png")
            png = self.diagnostics / (stem + ".png")
            probe(stem + "-pull", "pull", remote + ".png", str(png))
            require_startup_png(png, self.report["display"])
            before = probe(stem + "-windows-before", "shell", "dumpsys", "window")
            (self.diagnostics / (stem + "-windows-before.txt")).write_text(before)
            # CLI dump only includes the active root; the complete WindowManager
            # dump on both sides rejects other/hidden error windows and focus races.
            # https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java
            dumped = probe(stem + "-dump", "shell", "uiautomator", "dump", remote + ".xml", timeout=45)
            if dumped.strip() != "UI hierchary dumped to: " + remote + ".xml":
                raise ValueError("UIAutomator did not report a fresh startup hierarchy")
            xml = probe(stem + "-xml", "shell", "cat", remote + ".xml")
            (self.diagnostics / (stem + ".xml")).write_text(xml)
            after = probe(stem + "-windows-after", "shell", "dumpsys", "window")
            (self.diagnostics / (stem + "-windows-after.txt")).write_text(after)
            window = startup_error_window(before)
            if startup_error_window(after) != window:
                raise RuntimeError("Startup error window changed during UI observation")
            target = startup_wait_target(xml, window, self.report["display"])
            live()
            record(stem, window=window, wait_target=target, screenshot=png.name, hierarchy=stem + ".xml")
            return window, target

        try:
            current_server("server-before")
            history("history-before")
            window, target = snapshot("before")
            initial = history("history-before-action")
            if window is not None:
                if len(initial["cold_system_ui_anrs"]) != 1:
                    raise RuntimeError("Recovery requires exactly one pre-boot current-server System UI ANR")
                proof.update(window=window, wait_target=target, wait_attempts=1)
                # The only recovery input: never Back, Close, force-stop, or a watcher.
                probe("wait-tap", "shell", "input", "tap", *map(str, target))
                while True:
                    raw = probe("windows-clearing", "shell", "dumpsys", "window")
                    observed = startup_error_window(raw, settling_window=window)
                    history("history-after-wait")
                    if observed is None:
                        break
                    # The same old window may be exiting or lose focus while it
                    # disappears. This permits waiting only, never another input.
                    pause(min(2, remaining(deadline, self.now)))
                observed, _ = snapshot("after-wait")
                if observed is not None:
                    raise RuntimeError("New or repeated startup error dialog after clearance")
            # Wait clears Android's not-responding flag; it is not health proof.
            # Revalidate actual services/unlock and the SAME server, then require
            # one more clear window observation. A new dialog never receives a tap.
            self.wait_ready(deadline)
            wait_for_unlock(self.adb, lambda: self.emulator.poll() is None, deadline, now=self.now,
                            pause=pause, record=lambda raw: (self.diagnostics / "startup-ui-unlock.txt").write_text(raw))
            current_server("server-after")
            # A final WindowManager observation catches any new error without
            # starting UIAutomator a third time on this CPU-bound guest.
            final = probe("windows-final", "shell", "dumpsys", "window")
            (self.diagnostics / "startup-ui-windows-final.txt").write_text(final)
            if startup_error_window(final) is not None:
                raise RuntimeError("New or repeated startup error dialog before controller")
            current_server("server-final")
            history("history-final")
            live()
            proof["verified"] = True
            record("verified", **proof)
            live()
        except (OSError, ValueError, RuntimeError, TimeoutError) as error:
            proof.update(verified=False, error=str(error))
            record("failed", **proof)
            raise

    def boot(self):
        deadline = min(self.deadline, self.now() + self.profile["boot_timeout"])
        command = emulator_command(self.sdk, self.diagnostics.resolve())
        self.report.update(status="booting", emulator_command=command)
        with (self.diagnostics / "emulator-stdout.txt").open("w") as output:
            self.emulator = subprocess.Popen(command, env=self.environment, stdout=output,
                                             stderr=subprocess.STDOUT, start_new_session=True)
        self.report["emulator_pid"] = self.emulator.pid
        try:
            self.capture_host_resources("boot-start", deadline)
            self.wait_ready(deadline)
            # Successful binder lookups alone do not prove the input service can execute.
            # Software cold-start input may take minutes. It gets the remaining shared
            # boot budget, rather than a separate shorter limit or a renewed deadline.
            self.adb("shell", "input", "keyevent", "82", deadline=deadline, check=True,
                     timeout=remaining(deadline, self.now))
            wait_for_unlock(self.adb, lambda: self.emulator.poll() is None, deadline, now=self.now,
                            record=lambda raw: (self.diagnostics / "unlock-state.txt").write_text(raw))
            # Apply the same required settings before waiting for boot receivers,
            # so their work need not follow broadcast completion. This is not
            # boot proof; every subsequent readiness gate still runs.
            for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                self.adb("shell", "settings", "put", "global", setting, "0.0",
                         deadline=deadline, check=True, timeout=30)
            self.report["boot_broadcast"] = wait_for_boot_broadcast(
                self.adb, lambda: self.emulator.poll() is None, deadline, now=self.now,
                record=self.record_boot_broadcast,
                log_reader=lambda deadline: read_native_boot_log(
                    self.diagnostics / "guest-startup-logcat.txt", deadline=deadline, now=self.now))
            self.wait_ready(deadline)
            self.require_display(deadline)
            self.require_startup_ui(deadline)
            self.report["status"] = "ready"
        except (OSError, ValueError, RuntimeError, TimeoutError, subprocess.TimeoutExpired):
            self.boot_failed = True
            raise
        finally:
            self.capture_graphics_backend()
            self.capture_host_resources("boot-ready" if self.report["status"] == "ready" else "boot-failed", deadline)
        print("API 29 boot flag, input/activity/package/window services and unlock succeeded", flush=True)

    def record_boot_broadcast(self, receipt):
        with (self.diagnostics / "boot-broadcast.jsonl").open("a") as stream:
            stream.write(json.dumps(receipt) + "\n")

    def wait_ready(self, deadline):
        def record(state):
            self.report["readiness"] = state
            (self.diagnostics / "startup.json").write_text(json.dumps(self.report, indent=2) + "\n")
        return wait_for_android(self.adb, lambda: self.emulator.poll() is None, deadline,
                                now=self.now, record=record)

    def capture_boot_failure(self):
        # Android 10 shell DUMP access suffices; exact tag filters are ANDed, so
        # collect the two tags separately. Missing/partial traces prove no readiness.
        # https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/services/core/java/com/android/server/DropBoxManagerService.java
        receipts = self.report["failed_boot_dropbox"] = []
        for tag, max_bytes in (("system_app_anr", 2 * 1024 * 1024),
                               ("system_server_watchdog", 1024 * 1024)):
            command = [str(self.sdk / "platform-tools/adb"), "-s", SERIAL,
                       "shell", "dumpsys", "dropbox", "--print", tag]
            deadline = min(self.deadline, self.now() + 10)
            try:
                receipts.append(capture_bounded_output(command, self.diagnostics / f"dropbox-{tag}.txt",
                    environment=self.environment, deadline=deadline, max_bytes=max_bytes, now=self.now))
            except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
                # Optional diagnostics must not replace the boot verdict or skip
                # owned-emulator teardown even if the collector itself fails.
                receipts.append({"command": command, "status": "error", "error": str(error),
                                 "deadline_monotonic_seconds": deadline})

    def close(self):
        cleanup_error = None
        try:
            if self.emulator is not None:
                if self.emulator.poll() is None:
                    if self.boot_failed:
                        self.capture_boot_failure()
                    # These read-only receipts run after the boot/controller verdict;
                    # they never authorize tests or extend a readiness deadline.
                    diagnostics = (
                        ("final-services.txt", ("shell", "service", "list")),
                        ("final-logcat.txt", ("logcat", "-b", "all", "-d", "-v", "threadtime")),
                        ("final-sensorservice.txt", ("shell", "dumpsys", "sensorservice")),
                    )
                    for name, command in diagnostics:
                        try:
                            result = self.adb(*command, deadline=self.now() + 10)
                            (self.diagnostics / name).write_text(result.stdout + result.stderr)
                        except (OSError, TimeoutError) as error:
                            self.report["errors"].append(f"Final diagnostics: {error}")
                # The launcher may disappear during final adb probes, or leave a
                # QEMU child in its process group. Either case must retain evidence.
                try:
                    os.killpg(self.emulator.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    self.emulator.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(self.emulator.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    self.emulator.wait(timeout=10)
                self.report["emulator_exit_code"] = self.emulator.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            cleanup_error = error
            self.report.update(passed=False, status="failed")
            self.report["errors"].append(f"Emulator cleanup: {error}")
        finally:
            # Never upload disposable userdata, and never touch another AVD.
            try:
                shutil.rmtree(self.avd_home)
            except OSError as error:
                cleanup_error = error
                self.report.update(passed=False, status="failed")
                self.report["errors"].append(f"Disposable AVD cleanup: {error}")
            (self.diagnostics / "startup.json").write_text(json.dumps(self.report, indent=2) + "\n")
            # android.py requires a fresh directory. Attach startup evidence only after it exits.
            self.out.mkdir(parents=True, exist_ok=True)
            shutil.move(str(self.diagnostics), str(self.out / "software-emulator"))
        if cleanup_error:
            raise RuntimeError(f"Software emulator cleanup failed; diagnostics retained: {cleanup_error}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("controller", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.controller[1:] if args.controller[:1] == ["--"] else args.controller
    session = None
    try:
        profile = next(profile for profile in load_profiles() if profile["id"] == args.profile)
        require_software_profile(profile)
        if not command or args.out.exists():
            raise ValueError("A full controller command and new evidence directory are required")
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ValueError("Android SDK must be provisioned by setup-android")
        session = SoftwareSession(profile, args.out, sdk)
        session.provision()
        session.boot()
        session.report["status"] = "controller"
        result = subprocess.run(command, env=session.environment, timeout=remaining(session.deadline))
        session.report.update(passed=result.returncode == 0, controller_exit_code=result.returncode,
                              status="completed")
        return result.returncode
    except (OSError, ValueError, RuntimeError, TimeoutError, subprocess.TimeoutExpired, StopIteration) as error:
        if session:
            session.report["errors"].append(str(error))
            session.report["status"] = "failed"
        print(f"Software emulator failed: {error}", file=sys.stderr)
        return 1
    finally:
        if session:
            session.close()


if __name__ == "__main__":
    raise SystemExit(main())
