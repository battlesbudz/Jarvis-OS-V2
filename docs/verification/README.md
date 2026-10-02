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
3. Run `.github/workflows/android-sandbox.yml` on disposable accelerated Android
   emulators: API 30 uses `app-release.apk`, API 35 uses `app-compact.apk`. ARM
   translation must support the shipping ARM64 payload.
4. Run every named method in `scripts/verification/scenarios.json`, retain a
   screenshot and UI hierarchy per scenario, then kill/relaunch the app to
   verify persisted model selection. That JSON is the current test contract;
   historical test counts in old reports are not current requirements.
5. Consolidate exact same-run JVM, APK and emulator evidence, rehash artifact
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
  --out verification-runs/manual/device \
  --source-commit "$(git rev-parse HEAD)" --allow-emulator-reset
```

The full gate clears app data and refuses non-emulators. Never target a personal
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
