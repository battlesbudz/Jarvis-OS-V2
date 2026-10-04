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
   software emulation (`-accel off -feature -HVF`). GitHub's M1 VMs do not
   support nested hardware virtualization. This profile alone uses the official
   [Emulator archive](https://developer.android.com/studio/emulator_archive)
   Canary 37.2.6 Apple Silicon package, build 16138043, as a controlled version
   experiment after Build 937's SystemUI crash/ANRs and previous-APK install
   timeout. The full official ZIP is 419,847,722 bytes with SHA-256
   `ca9eeb7857771de6219591a70b39342ac2d056b7701d1df0d0c719f41260f4a5`;
   its size/hash are checked before safe staged extraction. Executable modes and
   SDK package metadata are preserved, `source.properties` must match, and both
   staged and installed binaries must report version 37.2.6/build 16138043 before
   boot. Invalid input retains the previous emulator; a failed installed-version
   check restores it. Download, verification and replacement share one 600-second
   provisioning budget within the existing 60-minute job limit. This is a
   hypothesis requiring a complete device pass, not evidence that the emulator
   version caused or repairs the failure. The software-specific launcher
   `scripts/verification/software_emulator.py` retains provisioning/emulator
   output and guest logcat, and requires the boot flag, input/activity/package/
   window services, successful unlock and actual user0 BOOT_COMPLETED delivery
   for the current system_server before invoking the same full release
   controller. Completion is read live from a bounded 256 KiB complete-line tail
   of that launch's native guest log, between fresh matching system_server PID
   probes. The exact user0 completion marker, a running emulator and observation
   before the original deadline remain required; a retained artifact alone cannot
   authorize readiness. This avoids Build 944's repeatedly timed-out guest logcat
   dumps without extending a deadline. All readiness and unlock checks share the 15-minute boot budget;
   software rendering uses the supported `-gpu swiftshader` selector, with the
   installed binary's supported modes and actual startup backend retained as
   diagnostics. Build 946's `software` selector chose GLES SwANGLE and Vulkan
   Lavapipe; Android's system process was killed twice before boot completion,
   with UI and foreground handlers blocked. The explicit SwiftShader trial
   tests a different supported GLES/Vulkan selection; it does not establish a
   rendering cause or faster boot. Two vCPUs, 2 GiB RAM,
   `vm.heapSize=256M` and a 540×960 framebuffer at 210 dpi preserve Pixel 2's exact
   Android dp viewport.
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
