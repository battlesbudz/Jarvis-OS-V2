# Audio/Muse integration acceptance — 9 October 2026

## Source and publication boundary

- Audio parent: `a86c3cc439b550c8bbb52bf223d81458ae6066b4`, verified by
  [Build 1190](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37751529144).
- Muse parent: `e413a89f9249e47484db62d18e4db8d11f190d54`, verified by
  [Build 1229](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37859655257).
- This is a two-parent integration. Neither branch's separate green run proves
  the merged code. No main merge, branch deletion, force push, model-weight or
  credential publication is part of this work.
- Build the merged source on `feature/muse-tools` using the retained complete
  audio workflow, then fast-forward `audio-pr2` to the same accepted commit.
  Existing PR #6 remains open. Check remote identities and exact-head required
  runs after each authorized publication.

## Observable acceptance

| Boundary | Required success, failure and preserved behavior | Evidence class |
| --- | --- | --- |
| Native model ownership | One active audio lifecycle; turn begin/finish and awaitIdle drain before release; failed drain preserves quarantine and exact error; benchmark reset cannot erase borrowed history | JVM, native, exact-build Android |
| Model admission and UI | Conversation and explicit reliability check share atomic admission; only exact model-file/suite reports appear; speed samples and compatibility caveats stay visible; old fingerprints disappear | JVM/Compose, release Android |
| Durable actions | Startup recovery and exact due-occurrence authority remain; effects are never replayed after uncertain outcome; Wisp observes redacted durable boundaries without gaining authority | JVM, release Android/process loss |
| Camera | Ended/stale call starts cannot revive capture; teardown invalidates in-flight frames; new calls retain their own capture and notification generation; permission denial stays audio-only | JVM, release Android; physical camera remains separate |
| Workflow scripts/export | Script parsing/evaluation terminates under bounded source/depth/operation limits; malformed input gives typed failure; personal content and credential literals are not exported | JVM, release Android |
| Browser | Runtime gate stays closed; approved-DOM replacement invalidates retained browser authority; no newly exposed browser dispatch | JVM, release Android |
| Release provenance | Same-run signed normal/compact APKs; all native pins and strict oracle; real prior-APK replacement without data clearing; five required profiles including observed 16384-byte pages and actual Fold resizing; exact-byte receipt | Hosted release workflow |

## Reconciliation decisions

- Retain the active audio `CheckedConversationLifecycle`; import only the needed
  `ToolCallEngine` contract and overrides. Do not replace production drain with
  Muse's dormant lifecycle helper or an empty `awaitIdle`.
- Retain startup recovery, exact workflow occurrence admission and activity
  observers while adding Muse script execution and privacy-safe task metadata.
- Keep both Wisp and camera release journeys. Camera becomes test80; Wisp stays
  test49. Tests 01–80 plus90 are all required. Unique names/numbers and source
  parity are checked by the helper contract.
- Preserve audio's bounded held/sparse accessibility discovery in test45 instead
  of adding a second independent polling budget. No product assertion is removed.
- Preserve every required release ABI keep from both parents. VideoCallService
  is a narrow Android notification entry point in the architecture guard, with
  no new permission to access runtime composition from the service.
- Combine measured-speed UI with fingerprint-bound reliability and retain
  caution that fixture agreement does not prove successful phone actions.

## Prior issue acceptance mapping

This is a fresh source/regression reconciliation, not a claim that a previous
successful branch build closed every issue. The exact merged release must still
compile and execute all named tests.

| Reported issue | Current implementation and regression evidence |
| --- | --- |
| Camera outliving farewell; stale service/status resurrection | `finishVoiceCall` routes the exact ended call into `refreshVideoStatusAfterFarewell`; `VideoCaptureStartFence` rejects delayed service starts and stale call-began callbacks. `CallVisionControllerTest`, `VideoCaptureStartFenceTest`, `FarewellNotificationRefreshTest` and release test80 cover owning/stale IDs, live-state notification derivation and no resurrection. |
| Async camera bind, off-main/failed/timed-out detach and false ACTIVE state | `CameraXVideoBinder` cancels late attach, retains unresolved cleanup ownership and reports `BindResult`; `MainThreadCameraHandle` posts the actual detach to main and reports its bounded outcome. `CallVisionController` keeps cleanup pending through denied permission. Binder/controller/main-thread-handle tests retain each failure boundary. Generation-fenced frame delivery avoids stale cache/observer work. |
| Advertised browser runtime absent | `BrowserRuntimeGate` remains false with no production enabler. Catalog/router hide browser operations and the unwired Android executor reports honest failure. `ActionTurnPlanBrowseGateTest` and release test76 retain this CLOSED status; production browser availability is not claimed. |
| Stale browser refresh/approval and non-atomic DOM submit | `AndroidBrowserExecutor.freshPage` refuses failed refresh. `BrowserSubmitTarget` passes form identity and DOM fingerprint into the same JavaScript evaluation that checks and dispatches. Extraction stamps the form before deriving its baseline. Executor tests, real extraction/submission `BrowserSubmitJsTest`, and release test77 cover changed/replaced/unstamped targets and zero submission. |
| Stale credential-field journaling and false autofill attribution | `secretArgumentKeys` fail-closes stale fields before durable creation; execution receives the original request while observers/storage receive the redacted copy. Credential fill checks the live host/field/DOM and empty-to-filled transition; its receipt does not attest a password manager. Journal, executor and `BrowserCredentialFillJsTest` regressions cover stale IDs, replaced DOM, pre-filled fields, dismissal and self-stamping. |
| Unwired script execution, host/readiness under-declaration, while-return/cancellation and parse bounds | `WorkflowCoordinator` supplies the real allowlisted interpreter on start/resume/alarm. Import review unions explicit metadata with step declarations, including function-free runtime requirements. `ScriptRuntime` returns typed EOF/depth failures, groups bounded unary runs and preserves operation/cancellation charging. `M5WorkflowsTest`, `ScriptRuntimeHardeningTest` and release test78 cover these contracts. |
| Tool/Adaptive/nested-condition export privacy and exact regex restoration | One argument-redaction path covers Tool and Adaptive candidates; screen typing and adaptive goals become setup bindings. Branch patterns use a valid quoted whole-value placeholder and exact restoration. `M5WorkflowsTest`, `BranchConditionExportPrivacyTest`, `WorkflowPersonalContentExportTest` and release test79 cover preview, nesting, distinct bindings and exact roundtrips. Retained free-form strings still require the export review; this is not a universal arbitrary-secret detector. |
| Minified release DTO linkage and test79 fixture validity | Narrow Proguard keeps retain manifest/setup/export DTOs and facade entry points. Test79 uses an admissible `post_notification` workflow before asserting redaction, preview and parsed manifest. The normal open-website eligibility rule stays unchanged. Exact merged release instrumentation must prove the app/test DEX boundary. |
| Wrong-window or replaced screen approvals | `ScreenControlSession.verifyTarget` plus `ServiceScreenBridge.withLiveNode` check identity/window/content again immediately before the effect. `M1cScreenControlTest` covers different-window and same-window replacements; release tests55–59 retain the Android path. |
| Nested workflow wait continuation | `WorkflowEngine.executeSteps` preserves ancestor path, leaf position, completed IDs and step results during resume. `M2WorkflowsTest` covers then/else/deeply nested waits, retained bindings and duplicate alarms without repeated effects. |
| MCP reconnect clearing pending schema review | `McpRegistry` preserves reviewed/pending hashes through disconnect and refresh failures, promoting only explicit acknowledgement or a genuine return to the reviewed schema. `McpRegistryTest` covers network/auth/version/discovery failures and reconnect. |
| Dormant native safety helpers or lost durable/Wisp integration | The active audio engine owner, strong quarantine retention, history reset/drain, `DurableTaskRecovery` and `AgentActivityMonitor` wiring are retained. Focused lifecycle/journal/recovery regressions run against the combined source. |

## Verification status at source publication

The local environment has JDK21 and reusable Kotlin/JUnit artifacts, but no
Gradle, Android SDK, adb/emulator or release signing configuration. Architecture,
Python/helper and focused JVM checks can run locally; they do not substitute for
full release compilation or hosted native/emulator verification. Exact commands,
counts and pending checks are recorded in the publication handoff. Any retained
failed run stays failed; this document does not retroactively qualify it.

Local pre-publication evidence (counts identify distinct suites; repeated runs
are not additional coverage):

- Architecture guard plus 96 general Python and 82 verification-helper tests.
- 113 streaming-SDK helper tests; these do not execute native model inference.
- 21 checked-native-lifecycle JVM regressions on the exact retained owner.
- 133 action/journal/workflow/script/export/recovery JVM checks.
- 142 additional browser/screen/workflow/MCP policy JVM checks.
- 18 real production JavaScript/credential-flow checks on Rhino 1.8.0, using
  scratch-only Android construction/clock seams. The unchanged Robolectric
  runners and real WebView/platform behavior still require hosted execution.
- 93 camera/controller/hub/admission/cleanup JVM tests against unchanged core
  methods extracted into a host-only harness. Android constructors, YUV/CameraX
  platform operations and the default Looper lookup are excluded; callbacks,
  locks, generation/ownership logic and test assertions are preserved. This
  executes the concurrency regressions without claiming Android compilation.
- All Kotlin source/test files parsed with the repository's Kotlin 2.3.21
  compiler PSI. Syntax parsing is not type checking or release compilation.

The combined candidate is **not yet APK-verified**. The hosted workflow must
finish all required jobs before release handoff. Physical microphone/speaker,
Bluetooth/OEM routing, real camera optics, on-device model accuracy and Fold6
thermal/performance behavior remain explicit device-signoff limits even after
an emulator release gate succeeds.
