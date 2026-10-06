# Release verification runbook

Start with [CONTRIBUTING](../../CONTRIBUTING.md), [the architecture map](../architecture/README.md)
and [the feature acceptance map](features.md). Agent changes also follow
[the repository verification skill](../../.agents/skills/jarvis-verify/SKILL.md)
and [AGENTS.md](../../AGENTS.md).

## Authoritative release gate

`.github/workflows/android.yml` defines the gate. Same-repository PR updates and
pushes to `main`, `PR1`, `feature-tools` and `feature/**` are opted in. Work on
`audio-pr2` uses existing PR #6. A feature with an open PR can trigger both a
branch-head build and a separate PR merge-candidate build; they test different
commits. Do not create or merge another PR without Justin's permission.

1. Check native keyword models and Python helpers, run all release JVM tests,
   and build the signed/minified production APK and release instrumentation APK.
2. Build the compact APK; verify native/DEX/asset equivalence, speech packaging,
   Piper callback ABI and signatures. Both variants have increasing build codes
   and use the existing signing identity.
3. Run `.github/workflows/android-sandbox.yml` on disposable Android
   emulators from `scripts/verification/profiles.json`: API 29/30/35/36,
   an API 36 foldable and a true 16 KB system image. The foldable uses the SDK's
   genuine `pixel_fold` hardware definition from pinned command-line tools
   23.0, build 16111833: a 2208×1840 inner screen, a 1080×2092 cover display and
   a 0–180° hinge. The fold job promotes the verified pinned tools directory to
   `cmdline-tools/latest`, which emulator-runner actually consumes, and retains
   its catalog receipt. The controller still requires real fold/unfold commands and
   actual display-size changes. API 30 and newer use
   accelerated x86-64 images with ARM64 translation. The current API 29 x86
   Google APIs and Play images contain no ARM64 bridge, so that profile uses
   the official AOSP `system-images;android-29;default;arm64-v8a` image on a
   standard `macos-15` ARM64 runner with explicit
   software emulation (`-accel off -feature HostComposition,-HVF,-Vulkan`). GitHub's M1 VMs do not
   support nested hardware virtualization. This profile alone uses the official
   [Emulator archive](https://developer.android.com/studio/emulator_archive)
   Stable 32.1.15 Apple Silicon package, build 10696886, as a controlled
   API 29 compatibility trial after Build 958's host GLES 2 context failed
   SurfaceFlinger's GLES 3 initialization. Build 963 initialized guest GLES 3
   through ANGLE/Vulkan SwiftShader; it did not establish a direct-only path
   or complete boot. Official indexed archive metadata
   binds the version/build/filename, 265,751,100-byte size and SHA-256
   `f70d764fd756664bc782bb24f8da67cbaa51d7e5ffac732108b9e6545cd9faf4`;
   its size/hash are checked before safe staged extraction. Executable modes and
   SDK package metadata are preserved, `source.properties` must match, and both
   staged and installed binaries must report version 32.1.15/build 10696886 before
   boot. Invalid input retains the previous emulator; a failed installed-version
   check restores it. Download, verification and replacement share one 600-second
   provisioning budget within the existing 60-minute job limit. This is a
   hypothesis requiring a complete device pass, not evidence that the emulator
   version caused or repairs the failure. The software-specific launcher
   `scripts/verification/software_emulator.py` retains provisioning/emulator
   output and guest logcat, and requires the boot flag, input/activity/package/
   window services, successful unlock and actual user0 BOOT_COMPLETED delivery
   for the current system_server before invoking the same full release
   controller. The three required zero-animation settings are checked after
   successful unlock and before waiting for boot receivers, so they need not
   run serially after broadcast delivery. Their existing command caps and the
   shared boot deadline remain unchanged; these writes are not readiness proof.
   Completion is read live from a bounded 256 KiB complete-line tail
   of that launch's native guest log. A local candidate scan avoids repeatedly
   launching guest PID probes while that completion marker is absent. A candidate
   alone cannot authorize readiness: the log is read again between fresh matching
   system_server PID probes. The exact user0 completion marker, a running emulator and observation
   before the original deadline remain required; a retained artifact alone cannot
   authorize readiness. This avoids Build 944's repeatedly timed-out guest logcat
   dumps without extending a deadline. All readiness and unlock checks share the 15-minute boot budget;
   the API 29 launcher explicitly adds `HostComposition` to its feature request.
   Emulator 32 disables that capability by default below API 32, although the
   inspected API 29 revision 8 image's `advancedFeatures.ini` declares it supported. Build 986
   lacked the host-composition extensions and repeatedly crashed the guest
   composer in `GoldfishGralloc::getHostHandle`, then lost SurfaceFlinger and
   restarted Android. Build 1003 and its unchanged-code retry advertised both
   host-composition extensions and no longer hit that native crash loop; services,
   input and unlock succeeded, but final user0 boot delivery still timed out.
   Complete startup and release-controller coverage remain unverified. The requested
   enabled and disabled features are retained separately from actual startup
   output. It does not disable watchdogs or change any readiness/test deadline.
   After full boot delivery, the launcher checks for a retained cold-start
   System UI ANR dialog before installing any Jarvis APK. It may select Wait
   once only for the exact Android-owned System UI dialog, retaining its UI
   evidence and requiring dismissal plus fresh readiness. Other, ambiguous or
   repeated error dialogs fail; no recovery runs during app tests. All of this
   consumes the original boot deadline. The retained cold-start ANR remains
   evidence and is never converted into app-test coverage.
   Software rendering requests `-gpu swiftshader_indirect`, with the
   installed binary's raw GPU/feature help and actual startup backend retained as
   diagnostics. Both help commands share the original provisioning budget and
   are individually bounded by 15 seconds; help text does not authorize readiness.
   Build 946's `software` selector chose GLES SwANGLE and Vulkan
   Lavapipe; Android's system process was killed twice before boot completion,
   with UI and foreground handlers blocked. Builds 947/948 requested SwiftShader
   and selected Vulkan SwiftShader while GLES remained SwANGLE; Android 10 still
   suffered platform ANRs/watchdogs. The next API 29-only compatibility trial
   disables the guest Vulkan feature using the documented `-feature -Vulkan`
   option. It retains SwiftShader and disabled HVF. Requested features and the
   actual backend are separate receipts: this does not prove Vulkan caused the
   failure or that all host Vulkan use disappears. See the official
   [troubleshooting guide](https://developer.android.com/studio/run/emulator-troubleshooting).
   The older ARM64 TCG source omits its generated SMP argument when HVF is
   disabled; trailing `-qemu -smp 2` preserves the requested two-vCPU setting.
   Actual installed execution, backend/GLES capability, CPU count and complete
   device coverage remain fresh-run requirements. The AVD returns to the more
   stable two-vCPU baseline after one-/three-vCPU trials also failed boot delivery
   and incurred system-server restarts. The API 29-only trial disables all 12
   optional emulated sensor flags: accelerometer/uncalibrated accelerometer,
   gyroscope/uncalibrated gyroscope, orientation, light, proximity,
   magnetic field/uncalibrated magnetic field, pressure, humidity and temperature.
   Jarvis has no SensorManager consumers; the pinned emulator and Android 10
   framework support an empty sensor list. Automatic-brightness/proximity sensor
   availability changes on this fixture, while microphone/audio configuration and all
   other profiles remain unchanged. The existing UIAutomator display-rotation,
   actual-dimension and continuity assertions remain required; they do not depend
   on sensor-driven auto-rotation. Cleanup retains a bounded read-only sensorservice
   receipt after the verdict. After a failed boot only, separate read-only
   `system_app_anr` and `system_server_watchdog` DropBox traces are also retained,
   capped at 2 MiB/1 MiB and ten seconds each within the existing session deadline.
   Partial/missing traces remain diagnostic gaps; collection cannot change the
   failed verdict or authorize tests. This is a load-reduction trial, not a proven startup
   fix: actual admission, boot and the complete exact-head gate must pass. No
   larger runner, hardware acceleration or relaxed deadline is used.
   The unchanged memory requests are
   2 GiB RAM and `vm.heapSize=256M`. Build 962's older wrapper interpreted that
   heap request as zero and promoted it to its 512 MiB minimum; its generated
   hardware and kernel arguments record 512 MiB, not a verified 256 MiB guest
   heap. The next trial keeps the request unchanged. The framebuffer is
   360×640 at 140 dpi. The API 29-only `hw.lcd.vsync=30` trial requests a
   30 Hz display cadence to reduce periodic work after retained 60 Hz CPU/UI
   stalls. Host/native `qemu.vsync` and guest `DisplayDeviceInfo` observations
   must establish the actual rate; the setting alone proves no improvement.
   Resolution, density, functional assertions and all deadlines remain unchanged.
   The same pinned binary advertises 140 dpi; actual fresh
   raster admission still needs observation. Build 962 rejected 210 dpi before
   guest startup. This trial preserves the exact physical dp
   extent and aspect ratio of Pixel 2 and Build 963's 720×1280 at 280 dpi,
   with one-quarter of Build 963's pixels. Density-specific resources, pixel
   rounding and window insets can still change layout, so the full layout gates
   remain required. Build 963's first system_server fatal was a permission-policy
   initialization timeout; this raster experiment does not establish its cause,
   a performance improvement or a boot cure.
   Physical size/density are observed before ready; optional read-only host
   resource receipts consume the existing deadline. These provisioning settings
   are experiments pending a complete passing run, not evidence of a memory cause.
   The job remains bounded by 60 minutes, with the existing 180-second APK install
   limit. A boot flag alone is not a passing
   device result. Missing services, ABI/page-size compatibility, a failed test
   or a timeout fails rather than skips. Linux profiles retain emulator-runner.
   The API 29 target is AOSP `default`; the other five image targets remain
   unchanged. Google's official Android system-image catalog currently lists
   stable revision 8 (`arm64-v8a-29_r08.zip`, 498,049,256 bytes, SHA-1
   `fa0d67d7430fcc84b2fe2508ea81e92ac644e264`). This avoids the Google APIs
   bundle whose framework permission initialization and watchdog failed under
   software emulation in Build 923. Faster usable startup is an inference to
   verify in a fresh run; the actual API, ARM64 ABI, services, cold input,
   observed unlock, full controller and both time limits remain required.
   Catalog: https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml
   Genuine Pixel Fold transitions may show the disposable keyguard. After the
   actual display-size change, the layout journey wakes the device and observes
   keyguard dismissal within the same transition deadline before checking call
   and draft continuity. It never relaunches the activity to restore those states.
4. Run every named method in `scripts/verification/scenarios.json`, retain a
   screenshot and UI hierarchy per scenario, then run the separately retained
   external process-loss, upgrade, platform and layout phases. The previous
   numbered user APK is installed and populated before replacement without
   clearing its data; ordinary isolated journeys still use disposable resets. That JSON is the current test contract;
   historical test counts in old reports are not current requirements.
5. Run real recorded-speech inference with pinned Whisper/Moonshine host runtimes,
   audit both APKs for native 16 KB compatibility, and exercise actual prior-APK
   upgrades and platform/lifecycle/layout phases on every required profile.
6. Consolidate exact same-run JVM, APK and emulator evidence, rehash artifact
   bytes, reject failed/skipped/missing/duplicate outcomes, then publish both
   APKs in a numbered GitHub Release. Publication depends on all prior gates.

The independent release test DEX shares the app's class loader.
`app/proguard-rules.pro` therefore preserves the runtime/framework interfaces
referenced by instrumentation, including lazy-layout methods. Recheck this ABI
boundary when moving classes or changing test dependencies. Do not substitute
an unshrunk/debug build for release verification.

## API 35 16 KB emulator ART compatibility trial

The `35-16k-normal` profile retains API 35, `google_apis_ps16k`, x86-64 with
ARM64 translation, runtime `PAGE_SIZE=16384`, SELinux enforcing, and every
release/upgrade/native/layout/lifecycle gate. Stock revision 5
(`AE3A.240806.043/12960925`) repeatedly crashed platform processes while reporting
`CollectorTypeCMC`. The [AOSP kernel compatibility fix](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73)
explains that x86-64 16 KB page-size simulation is incompatible with UFFD/CMC.
AOSP ART's [odrefresh regression test](https://android.googlesource.com/platform/art/+/8222aa2d2df6273da689f0edd3913e8370c0c1c2)
exercises the supported `runtime_native_boot/force_disable_uffd_gc=true`
DeviceConfig override followed by a reboot to select concurrent copying (CC).

This profile alone records its original 300-second deadline immediately before
initial emulator launch. `scripts/verification/runtime_gc.py`, called by the
existing Android controller, writes the flag once, requires both DeviceConfig
and persisted-property readback, and reboots once. The initial boot, propagation,
reboot, complete ART regeneration, odsign verification and fresh service/input readiness
all consume that same deadline. A changed boot ID, unchanged API/page-size/ABI/
bridge/SELinux identity, the current system server's exact CC log and current
zygote parent (including process start ticks) are required before any APK installation.
Because odsign can verify partially compiled artifacts, its success property alone
is insufficient: the current boot must also log the exact `odrefresh compiled all artifacts, returned 80` success. Partial or failed compilation is rejected, following the distinct
[odsign result branches](https://android.googlesource.com/platform/system/security/+/android-15.0.0_r1/ondevice-signing/odsign_main.cpp#588). Its real first-launch
Jarvis PID must independently report CC before the ordinary journeys run.
Missing, wrong, stale or late evidence fails; it never selects a replacement
image, retries a reboot, relaxes a timeout or skips an existing check.

The artifact retains the prelaunch receipt, bounded logs from each setup boot,
intentional reboot boundary, identities/readbacks, complete-compilation and system collector receipts, and
immediate first-launch Jarvis collector receipt. The foldable prelaunch hook and
all other profiles keep their behavior. This is a configuration trial pending a
fresh full hosted result; local helper tests do not establish that ART's work
fits 300 seconds. A pass establishes the simulated runtime's 16 KB compatibility
and native loading checks, not physical 16 KB hardware, OEM/ARM64 behavior,
acoustic/model correctness, or device performance.

## Run identity and evidence

Find the run for the exact changed branch head. Record its URL, job conclusions,
`pr_head`, tested `source_commit`, APK SHA-256 values and receipt. On a PR event,
`source_commit` is GitHub's tested merge commit and `pr_head` is the branch revision;
on a push, `source_commit` is the pushed commit and `pr_head` is empty.

`scripts/verification/artifacts.py` selects artifacts using the current run,
source SHA and latest completed logical producer attempt, with timestamp-window
checks. It downloads by artifact ID, validates metadata and safely extracts ZIPs.
Numeric artifact ordering is not provenance. Old failed-attempt artifacts remain
evidence and are never substituted for the current result.

The `jarvis-verification-receipt` artifact contains `receipt.json` and a readable
summary. Emulator evidence includes `report.json`, `instrumentation.txt`, logs,
screenshots and UI XML. Verification artifacts are retained for 14 days; save
needed failure evidence before it expires. APK releases remain the phone handoff.
A prior green run or a successful build alone does not verify a new revision.

## Local checks and device exploration

Python 3.10+ runs the dependency/helper and harness logic suites:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts/verification -p 'test_*.py'
```

A full local Android build needs JDK 21, Gradle 8.10.2, SDK/platform 35,
NDK 27.2.12479018, CMake 3.22.1, adb, a compatible disposable emulator and the
existing signing environment. Android app bytecode targets Java 17; LiteRT host
tests require JDK 21. Hosted CI is the default when local SDK/KVM is unavailable.
Do not commit signing material, print credentials or copy CI signing secrets to
another environment.

Explore an already booted disposable emulator with:

```bash
python3 scripts/verification/android.py --serial emulator-5554 control launch --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control snapshot --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control tap 250 400 --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control back --out verification-runs/explore
```

Run the full local device gate using new output directories:

```bash
python3 scripts/verification/android.py --serial emulator-5554 run \
  --apk app/build/outputs/apk/release/app-release.apk \
  --test-apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --previous-apk verification-inputs/jarvis-previous-release/app-release.apk \
  --previous-metadata verification-inputs/jarvis-previous-release/previous-release.json \
  --profile 30-phone-normal \
  --out verification-runs/manual/device \
  --source-commit "$(git rev-parse HEAD)" --allow-emulator-reset
```

The full gate performs a previous-APK upgrade before clearing disposable fixture
data for its isolated regression suite, and refuses non-emulators. Its previous
APK and metadata must come from the checksum-verified release helper.
`local_gate.py` requires `JARVIS_PREVIOUS_APK`, `JARVIS_PREVIOUS_METADATA` and
`JARVIS_EMULATOR_PROFILE` in addition to its existing signing/device environment. Never target a personal
phone with stored conversations/models. Interactive control does not clear data.
Preserve evidence before resetting or stopping the device.

## Diagnose and repair

Classify each failure first: product regression, harness defect or infrastructure.
Read the raw report/logs and inspect screenshots for layout claims. Fix product
regressions in the app; explain harness corrections explicitly and retain every
acceptance assertion. Retry a transient infrastructure failure once when justified.
Do not cancel unrelated runs, weaken a gate or use application changes to hide
missing SDK/ABI/credentials.

The verification skill sets a default budget of three repairs and 60 minutes per
feature investigation. `scripts/verification/repair.py` and `local_gate.py` support
bounded local repair with an installed/authenticated coding CLI and existing
signing/emulator environment. Commands are argv arrays, not shell expressions;
the loop records attempts and stops automatic repairs that modify acceptance
infrastructure. See `python3 scripts/verification/repair.py --help` for options.
Hosted diagnosis and repair are performed by the active Work/Codex session;
GitHub Actions does not continue autonomous coding after that session ends.

For new Android journeys, update `ReleaseJourneyTest.kt` and `scenarios.json`
together. Keep failure cases and preserved behavior explicit in `features.md`.
[Modular-refactor acceptance](modular-refactor.md) describes structural changes.

## Coverage and handoff

The emulator checks production UI, storage and real Android action executors.
Controlled model responses and fake backends test logic, not actual weights.
It does not verify real-model inference/tool selection/approved-memory use,
physical microphone/speaker/Bluetooth/echo/interruption behavior, large real
model transfers or Fold 6 GPU/thermal/latency performance. These remain phone
checks and appear in the receipt's `not_covered` list.

Return the verified branch/commit/run, concise changes, passed checks, remaining
coverage gaps and GitHub Release link. Passing the gate produces a release
candidate for Justin's product signoff; it does not authorize merging a PR.

Historical implementation and failure records remain in [features.md](features.md),
[combined-audio.md](combined-audio.md) and the linked plans/diagnostics. Current
workflow code and scenario JSON take precedence over those historical snapshots.
