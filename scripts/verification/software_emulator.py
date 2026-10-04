#!/usr/bin/env python3
"""Bounded API 29 ARM64 software boot, then run the unchanged release controller.

SDK packages/AVD settings follow android-emulator-runner at a421e43855164a8197daf9d8d40fe71c6996bb0d:
https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/sdk-installer.ts
https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/emulator-manager.ts
Unlike that action, boot completion alone never authorizes an input command.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import time

try:
    from .profiles import load_profiles
except ImportError:
    from profiles import load_profiles

SERVICES = ("input", "activity", "package", "window")
AVD_NAME = "jarvis-api29-software"
SERIAL = "emulator-5554"
# Build 936 exhausted guest CPU during API 29 permission initialization. This
# bounded headroom/raster experiment retains two vCPUs and the Pixel 2 dp viewport;
# it does not establish host memory pressure as the cause or change any deadline.
SOFTWARE_AVD_SETTINGS = {"hw.cpu.ncore": "2", "hw.ramSize": "2048M", "vm.heapSize": "256M",
                         "hw.lcd.width": "540", "hw.lcd.height": "960", "hw.lcd.density": "210",
                         "disk.dataPartition.size": "4096M"}


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
            "-no-window", "-gpu", "swiftshader_indirect", "-noaudio", "-no-boot-anim", "-no-snapshot",
            "-timezone", "Etc/UTC",
            "-accel", "off", "-feature", "-HVF", "-show-kernel", "-logcat", "*:V",
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
                       "status": "provisioning", "errors": []}

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

    def provision(self):
        sdkmanager = str(self.sdk / "cmdline-tools/latest/bin/sdkmanager")
        self.run([sdkmanager, "--licenses"], timeout=120, input="y\n" * 100)
        self.run([sdkmanager, "--install", "build-tools;37.0.0", "platform-tools", "platforms;android-29"], timeout=600)
        self.run([sdkmanager, "--install", "emulator", "--channel=0"], timeout=600)
        image = "system-images;android-29;default;arm64-v8a"
        self.run([sdkmanager, "--install", image, "--channel=0"], timeout=600)
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
        self.run([str(self.sdk / "emulator/emulator"), "-version"])
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
            for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                self.adb("shell", "settings", "put", "global", setting, "0.0",
                         deadline=deadline, check=True, timeout=30)
            self.wait_ready(deadline)
            self.require_display(deadline)
            self.report["status"] = "ready"
        finally:
            self.capture_host_resources("boot-ready" if self.report["status"] == "ready" else "boot-failed", deadline)
        print("API 29 boot flag, input/activity/package/window services and unlock succeeded", flush=True)

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
