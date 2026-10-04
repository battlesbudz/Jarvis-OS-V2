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
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from xml.dom import minidom
import zipfile

try:
    from .profiles import load_profiles
except ImportError:
    from profiles import load_profiles

SERVICES = ("input", "activity", "package", "window")
AVD_NAME = "jarvis-api29-software"
SERIAL = "emulator-5554"
# Official manual-install package; this controlled API 29 diagnostic does not
# establish that this Canary version repairs framework startup/install failures.
# https://developer.android.com/studio/emulator_archive
EMULATOR_PIN = {
    "version": "37.2.6", "build_id": "16138043", "channel": "Canary",
    "url": "https://dl.google.com/android/repository/emulator-darwin_aarch64-16138043.zip",
    "size_bytes": 419847722,
    "sha256": "ca9eeb7857771de6219591a70b39342ac2d056b7701d1df0d0c719f41260f4a5",
}

# Build 936 exhausted guest CPU during API 29 permission initialization. This
# bounded headroom/raster experiment retains two vCPUs and the Pixel 2 dp viewport;
# it does not establish host memory pressure as the cause or change any deadline.
SOFTWARE_AVD_SETTINGS = {"hw.cpu.ncore": "2", "hw.ramSize": "2048M", "vm.heapSize": "256M",
                         "hw.lcd.width": "540", "hw.lcd.height": "960", "hw.lcd.density": "210",
                         "disk.dataPartition.size": "4096M"}
# Builds 947/948 requested SwiftShader but retained GLES SwANGLE, with SystemUI
# ANRs/system-server watchdogs. Disable the guest Vulkan feature as a documented
# compatibility trial; this does not prove a graphics cause or remove all host
# Vulkan use. Keep the selector and record the actual backend independently.
# https://developer.android.com/studio/run/emulator-troubleshooting
SOFTWARE_GPU_SELECTOR = "swiftshader"


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
    return [str(sdk / "emulator/emulator"), "-port", "5554", "-avd", AVD_NAME,
            "-no-window", "-gpu", SOFTWARE_GPU_SELECTOR, "-noaudio", "-no-boot-anim", "-no-snapshot",
            "-timezone", "Etc/UTC",
            "-accel", "off", "-feature", "-HVF,-Vulkan", "-show-kernel", "-logcat", "*:V",
            "-logcat-output", str(diagnostics / "guest-startup-logcat.txt")]


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
            receipt.update(file_size_bytes=before.st_size, start_offset=start, read_bytes=len(raw))
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
            state["completed"] = bool(re.search(pattern, probes["logcat"]["stdout"], re.MULTILINE))
        record(state)
        if state["completed"] and running() and now() < deadline:
            return state
        pause(min(2, max(0, deadline - now())))
    expired()


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
        self.report = {"schema": 1, "passed": False, "profile": profile,
                       "acceleration": "software", "boot_timeout_seconds": profile["boot_timeout"],
                       "status": "provisioning", "errors": [],
                       "graphics": {"requested_selector": SOFTWARE_GPU_SELECTOR,
                                    "requested_disabled_features": ["HVF", "Vulkan"]}}

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
        expected = {"size": "540x960", "density": "210"}
        for field, value in expected.items():
            result = self.adb("shell", "wm", field, deadline=deadline, check=True)
            display[field] = {"stdout": result.stdout, "stderr": result.stderr}
            (self.diagnostics / "display-state.json").write_text(json.dumps(display, indent=2) + "\n")
            if result.stdout.strip() != f"Physical {field}: {value}":
                raise RuntimeError(f"Software emulator physical {field} must be {value}: {result.stdout.strip()}")
        remaining(min(deadline, self.deadline), self.now)
        self.report["display"] = {"width": 540, "height": 960, "density_dpi": 210}

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
            self.report["boot_broadcast"] = wait_for_boot_broadcast(
                self.adb, lambda: self.emulator.poll() is None, deadline, now=self.now,
                record=self.record_boot_broadcast,
                log_reader=lambda deadline: read_native_boot_log(
                    self.diagnostics / "guest-startup-logcat.txt", deadline=deadline, now=self.now))
            for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                self.adb("shell", "settings", "put", "global", setting, "0.0",
                         deadline=deadline, check=True, timeout=30)
            self.wait_ready(deadline)
            self.require_display(deadline)
            self.report["status"] = "ready"
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

    def close(self):
        cleanup_error = None
        try:
            if self.emulator is not None:
                if self.emulator.poll() is None:
                    # Guest logs already stream from startup. Capture final binder state as well.
                    for command in (("shell", "service", "list"), ("logcat", "-b", "all", "-d", "-v", "threadtime")):
                        try:
                            result = self.adb(*command, deadline=self.now() + 10)
                            name = "final-services.txt" if command[0] == "shell" else "final-logcat.txt"
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
