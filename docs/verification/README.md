# Jarvis development sandbox

This implements phases 1–5 of the development workflow: persistent Codex instructions, disposable Android execution, app controls and evidence, regression scenarios, and bounded repair/retest. It runs outside Jarvis. The release artifact remains the existing signed ARM64 app.

## What runs automatically

Each same-repository PR update and push to `feature/**` triggers the existing `Android APK` workflow (alongside the existing `main` and `PR1` push routes):

1. Build native keyword checks, Python harness checks, all release JVM tests, the signed/minified release APK and its instrumentation APK.
2. Build the compact release APK and compare native/DEX/assets with the normal variant.
3. Run `android-sandbox.yml` on fresh GitHub Ubuntu runners with KVM. API 30 tests the normal APK; API 35 tests the compact APK. Google APIs x86_64 images supply ARM translation; the controller requires `arm64-v8a` in the runtime ABI list and fails if the image cannot run the shipping APK.
4. Execute twenty-one named release instrumentation scenarios, then kill/relaunch Jarvis in a separate process to check persisted selection. Retain screenshots, UI hierarchy, Android logs, test output, package metadata, APK hashes, source SHA and PR head.
5. Allow the existing publication jobs only after the build and both sandbox jobs pass. This creates a candidate for Justin's signoff; passing automation does not merge the PR or constitute product acceptance.

No production signing secrets are passed to the emulator job. It consumes already signed artifacts. Evidence expires after 14 days; download it from the run when keeping a long-lived investigation. Test reports and APKs are associated with the same workflow run. On a PR run, `source_commit` is GitHub's tested PR merge commit and `pr_head` identifies the contributor branch revision. On a feature-branch push, `source_commit` is the exact pushed commit and `pr_head` is empty. Both conventions are intentional; do not substitute a branch-head pass for a combined merge-candidate pass.

The release runner shares the app's class loader. `app/proguard-rules.pro` preserves the shared Kotlin/coroutine runtime, Lifecycle, tracing, futures, annotation interfaces and the action contract used by the integration tests. These shared dependencies were audited against the release test DEX's external method/field owners. Without these rules, separate shrinking can remove methods needed only by the runner and crash before tests start. The same rules apply to the shipped APK; ordinary app optimization remains enabled and the existing size reports record the tradeoff. Recheck this boundary when changing test dependencies.

## Persistent agent workflow

The repository skill is `.agents/skills/jarvis-verify/SKILL.md`; `AGENTS.md` points agents to it. Codex can invoke `$jarvis-verify`. In a Work session using a connected repository, ask it to read that skill and the current PR before implementing a feature. This repository skill is not a globally installed personal Work plugin.

The feature map describes coverage and gaps. Future features extend that map and add executable acceptance scenarios. No test-only menu, fake-model mode or remote command endpoint is added to the shipping app. Compose resource tags expose stable UI selectors without changing visible text.

## Local device control

Prerequisites: Python 3.10+, Java 21, Gradle 8.10.2, Android SDK/platform 35, NDK 27.2.12479018, CMake 3.22.1, adb, a compatible disposable emulator, and the existing release-signing environment. This Work container may lack SDK/KVM; the hosted runner is the default execution path.

```bash
python3 scripts/verification/android.py --serial emulator-5554 control launch --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control snapshot --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control tap 250 400 --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control swipe 250 800 250 300 400 --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control text 'Gemma' --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control back --out verification-runs/explore
```

For a full run, supply the two APK paths and a new evidence directory:

```bash
python3 scripts/verification/android.py run \
  --apk app/build/outputs/apk/release/app-release.apk \
  --test-apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --out verification-runs/manual/device \
  --source-commit "$(git rev-parse HEAD)" --allow-emulator-reset
```

The full run clears Jarvis data and refuses non-emulators. Never point this workflow at a personal phone with conversations/models. Interactive control does not clear data. Each run requires a new directory so failures are not overwritten by later success.

Instrumentation exports its screenshots and UI XML through Android's Downloads API into a unique `Download/jarvis-verification-<run>` directory. This works with Android 11 scoped storage without rooting the emulator or changing app permissions. The controller requires one valid PNG and XML per scenario before reporting success. These exports live only in the disposable test device and its retained evidence artifact.

## Bounded local repair loop

With an emulator already booted and the signing variables configured, this runs the release gate. If it fails, an already installed/authenticated Codex CLI can diagnose evidence, edit application code and trigger another full gate:

```bash
python3 scripts/verification/repair.py \
  --out verification-runs/feature-001 \
  --gate-command '["python3","scripts/verification/local_gate.py"]' \
  --repair-command '["codex","exec","--sandbox","workspace-write","Read AGENTS.md and the file named by JARVIS_FAILURE_REPORT. Diagnose gate.log and evidence, fix application code only, and preserve all acceptance criteria. Do not publish, push or merge."]'
```

Commands are argv arrays, not shell expressions. `JARVIS_ATTEMPT_DIR`, `JARVIS_SOURCE_COMMIT` and `JARVIS_FAILURE_REPORT` are supplied to the subprocess. Default limits are three attempts, 60 minutes overall, and 20 minutes per command. Both a successful exit and a fresh report for the source commit are required. The source digest records uncommitted repairs and detects source changes during verification. A repair that changes tests, workflows, harnesses, build configuration or agent instructions stops for review; changes are left visible for diagnosis, not silently reverted. Missing reports, skipped tests, crashes and no-progress repairs fail closed. Time limits terminate the subprocess group.

For hosted CI, the same bounded policy is followed by the active Codex/Work session using GitHub run logs and artifacts. There is no always-on paid AI service or new credential dependency. Finishing a conversation does not leave an autonomous repair agent running in Actions.

## Short multi-action phone turns

A final text or voice request may contain one to three explicit, ordered phone actions from the existing battery, media-volume, and installed-app contracts. Media-volume requests accept a bounded level from 0 through 100 with either plain numeric units or an explicit percent form. `ActionTurnPlan` parses and validates the complete plan before dispatch. Conditional language, more than three actions, unsupported clauses, and invalid volume values are rejected before an Android side effect. Native tool calls must strictly match the next planned request; malformed arguments, extra keys, wrong order, and unknown tools produce no dispatch.

`ActionTurnRunner` records an executor receipt for each dispatched request and advances only after that receipt. The receipt carries the executor result, so a success message is never synthesized without an executor response. A failed result stops the remaining steps and retains the receipts already produced in the coordinator outcome. A model replay of a completed request is acknowledged from its existing receipt and is not dispatched again; an explicitly repeated user step remains executable. Cancellation is rethrown by the pipeline/runner boundary, stops later actions, and leaves completed executor receipts durable and visible through the conversation history record. The focused JVM and Android journey tests cover this behavior; hosted Android CI remains pending.

Text and voice conversations use the same parsed plan, strict tool-call boundary, and Android executor. Immediate confirmations such as “Yes” or “open it” resolve only a specific app offer/request in the latest turn; stale history does not authorize an action. Voice adds a final-transcript guard that must agree with the confirmed request. The Android journeys use simulated model tool-call objects with the real Android executor; they do not demonstrate real model weights selecting the calls.

The natural-action repair makes the shared action plan authoritative before factual lookup. It covers requests such as “Can you open up Settings and tell me what my battery percentage is?” and the retry-prefixed literal “I said, can you open up the fistbook and tell me what my battery percentage is?”. The first routes to `open_app(Settings)` then `read_battery` without Wikipedia lookup. The second keeps `fistbook` literal; a model call substituting Facebook is rejected before dispatch, while the literal missing-app result stops before battery. The repair does not promise fuzzy ASR or a `fistbook` → Facebook alias.

The observed Build 753 phone failure had three separate routing boundaries: the original battery grammar rejected “tell me what my battery percentage is”; the retry prefix “I said, can you …” prevented the app clause from being recognized; and the factual lookup detector treated the substring “book” inside both `Facebook` and `fistbook` as a Wikipedia trigger. The repair uses one bounded battery grammar, an anchored retry-prefix normalization, and action-plan precedence before lookup, rather than an app-specific alias.

Build 753 run `35758930187` is historical evidence: it passed 578 JVM tests and 18 journeys per variant but predated these natural-wording journeys. Build 756 run `35762590799` was an artifact-only CI change and does not verify this repair. The current 20-scenario revision has a focused 53-test host pass; full release Android CI and real-model/device coverage remain pending.

## Coverage boundaries

The emulator exercises the actual release UI and Android action executor. It does not download gigabytes of model weights, invoke the real language model, or test acoustic behavior. Existing JVM tests use deliberate fake backends for many conversation/audio state machines. Those are logic coverage, not device evidence. Future real-model/GPU/audio tests need a separately provisioned suitable device runner and explicit scenarios; their absence is reported in every sandbox result.

The harness's own failure-injection tests run with:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
```

They demonstrate broken → repaired → retested, reject skipped/crashed/missing/duplicate instrumentation results, and stop attempts to weaken the gate or change source during a run.

## Design references

This is an original Jarvis-specific implementation of the persistent verification workflow described by [pstack](https://github.com/cursor/plugins/tree/main/pstack), adapted to Codex and Android. It does not require Cursor or copy the plugin's source. It uses [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator), Android's [release test variant support](https://developer.android.com/studio/test/advanced-test-setup), and the [Android emulator runner](https://github.com/ReactiveCircus/android-emulator-runner). ARM translation is documented by [Android](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html); physical-device performance still needs its own measurement.

## Consolidated CI receipt

`Android APK` now includes **Consolidate exact-build verification evidence** after both emulator jobs. Every sandbox, receipt, and publication consumer uses `scripts/verification/artifacts.py`: it binds each named artifact to the current run and source SHA, chooses the latest completed logical producer attempt, and then chooses the unique artifact created inside that attempt's producer start/completion window (with the helper's small upload clock allowance). Its direct artifact-ID downloader checks ZIP size and SHA-256 metadata before safely extracting the selected archive; GitHub credentials are used only for the API request and are not sent to the redirected storage host. It never orders by numeric artifact ID. Older failed-attempt artifacts remain retained as evidence, while consumers use only the selected current-attempt artifacts. The receipt job rehashes the normal, compact and instrumentation APKs, reparses all named instrumentation outcomes and JVM XML, and checks screenshot/hierarchy presence and persisted model selection. Both publication jobs require this gate. The `jarvis-verification-receipt` artifact contains `receipt.json` and a human-readable summary, retained for 14 days including failed validation.

The retry regression from run 751 is covered by the replay helper: the newer artifact had the lower ID `10707864350`, and timestamp/producer-attempt selection correctly selects `10707864350` instead of the older failed evidence. Ten focused helper tests and the existing eight receipt tests passed; a new full CI run is still pending. This documents workflow/helper behavior only; it does not claim an app change, a green run 751, or completed full CI.

Use the receipt's `pr_head` for the candidate branch and `source_commit` for the tested merge. Refresh the current branch before presenting a candidate: the receipt is historical evidence, not release authorization. The active Work session handles diagnosis/repair using existing connected GitHub access; no separate worker credentials, database or paid host are needed. Closing the session does not stop GitHub CI, but autonomous coding does not continue. Existing inference, physical audio and performance limitations remain.

Host test runtime: LiteRT-LM 0.16.0 ships Java 21 class files. CI uses Java 21 so tests can inspect its actual tool/config API. App Java/Kotlin output remains targeted at Java 17 and is desugared for Android. Build 747 retained the `UnsupportedClassVersionError` evidence that exposed this mismatch; no test was removed or weakened.

## Parallel feature branches

Create a separate branch such as `feature/memory-os` from the latest agreed `audio-pr2` base **containing this workflow update**. Use a separate checkout/worktree and an explicit file scope per chat. Pushes to `feature/**` run the same signed normal/compact APK build, JVM/native/helper checks, API 30/API 35 emulator journeys, and consolidated receipt. Other new branch names are not opted in. No new PR is needed to test a feature branch.

Download `jarvis-os-v2-release-apk` and `jarvis-verification-receipt` from that exact Actions run. Feature push runs do not invoke either release-publication job, change `audio-pr2`, or merge anything. Artifacts are associated with their own run, so simultaneous branches do not overwrite one another's APKs/evidence. Distinct runs use disposable hosted runners; GitHub's available concurrency may queue them. Do not cancel unrelated runs.

When an approved PR exists, a feature update can produce both a branch-head run and a PR merge-candidate run. These test different commits and both consume CI time. No automatic cancellation or cross-chat locking is added here. Keep publication and integration assigned to one chat; refresh the destination head and resolve conflicts rather than force-pushing over another chat's work.

For a large feature such as MemoryOS, keep an independent branch through small, tested milestones. Regularly incorporate agreed base updates into the feature branch and rerun its checks. Integrate a useful milestone only after the combined revision passes review and tests. Opening a PR proposes that integration; it does not merge automatically. Justin's explicit permission is still required to create a new PR or merge one. Preserve reserved PR #7 and its branch unless Justin specifically chooses to reuse it.

Branches created from an older base must first receive the workflow update to opt into testing. This is push-triggered support, not a manual-dispatch service or a guarantee that the default branch already contains the update. APK/emulator success does not verify real model inference, physical audio or device performance.
