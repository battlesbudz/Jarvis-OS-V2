# Tools epic integration log

Epic: [#8](https://github.com/battlesbudz/Jarvis-OS-V2/issues/8). Implementation branch: `feature-tools`.

## October 4 — M1b media control slice (`media_control` tool)

Branch: `muse/feature-tools` (agent working branch for this epic; no PR created or
merged). The first M1b command family beyond the existing three tools is implemented:
`media_control` with strict verbs play/pause/toggle/next/previous.

Changed files:
- `app/.../actions/MobileToolCatalog.kt` — new catalog entry; single `action` string
  parameter with `^(play|pause|toggle|next|previous)$` pattern; drives the LiteRT schema.
- `app/.../actions/MobileAction.kt` — new `MediaControlAction` enum (verb + receipt
  label) and `MobileAction.MediaControl`; validator binds exact verbs, rejects the rest.
- `app/.../actions/NativeActionDecoder.kt` — tolerant `media_control` arg mapping
  (strict `decodeStrict` already routes through the catalog).
- `app/.../actions/AndroidMobileActionExecutor.kt` — dispatches the matching media
  key down/up pair via `AudioManager.dispatchMediaKeyEvent`; receipt reports the
  dispatch honestly because Android does not confirm session consumption.
- JVM tests — `MobileToolCatalogTest` (schema parity, strict accept/reject, tolerant
  decode), `MobileActionValidatorTest` (verb mapping, rejection, typed pipeline
  delivery, no-executor-effect on invalid input).
- Release journey `test35_mediaControlDispatchesViaAudioManager` +
  `scripts/verification/scenarios.json` — all five verbs dispatch on the emulator
  through the real executor; unknown verb rejected; volume unchanged.
- `.github/workflows/android.yml` — push trigger and build-job condition now opt in
  `muse/feature-tools` so this branch gets the exact-revision build, both emulator
  variants, consolidated receipt and publication. No other workflow behavior changed.

Acceptance: per `.agents/skills/jarvis-verify/SKILL.md` — observable checks stated
before implementation (JVM catalog/decoder/validator/pipeline; Android test35;
existing test01–test34/test90 intact), failure case (unknown verb → rejected, no
side effect), real-model selection and physical Fold 6 media behavior explicitly
labeled unverified.

Publish: local commit `2a4a46e` → GitHub `muse/feature-tools` at
`90a53a8a99bbf0cf714dc19158f55c9740ffe99d` via the git-database API (16 files;
`.github/workflows/android.yml` excluded from the pushed tree because the stored
credential lacks the Workflows permission — 403 on any tree containing a workflow
path. The `muse/feature-tools` opt-in is retained in the local commit only and
needs a privileged push to land). CI verification runs on branch
`feature/tools-m1b-media` (same commit), which matches the existing `feature/**`
push trigger: run [37176105910](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37176105910).
Exact-revision gate evidence to be recorded here on completion.

M1b remaining: website/settings/map destinations. M1c/M1d/M1e, M2–M8, A0–A6 still open.

## September 30 — publish every verified build

Build [851](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36668555984) at
`ca99845d368cfabcf76097404f26861e4477ffe9` passed the build, both emulator variants and
receipt, then published normal/compact APKs in
[v0.1.0-build.851](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/v0.1.0-build.851).

Justin clarified that each successful build must provide a numbered GitHub Release with
normal and compact APKs for testing. Build 849 passed all release checks but its feature
push failed the publication event condition. The `publish` job now accepts all opted-in
push builds, retaining its build, both sandbox and consolidated receipt dependencies.
Release names include the source branch. No PR or merge is needed for a feature release.
Static workflow checks passed; hosted publication verification is pending this commit's run.
The earlier no-release feature-branch behavior below is historical and superseded here.

## September 30 — finish M1a ownership and authority

Intake head: `ca99845`. Refreshed audio remains `b54bb98`; Memory OS advanced to
`90a83a8` (wiki-tab alignment/release Compose fixture fields). Neither source is imported
or advanced by this action-owned change. This completes the M1a implementation before newer autonomy
work. A schema 2 journal atomically persists task groups, ordered steps, attempts, exact
approvals, routine-grant provenance and progress events. Schema 1 migrates conservatively.
Normal text/voice and direct-app dispatch admit the frozen plan once; results bind to a
unique attempt and generation. Approval consumption and dispatch eligibility commit together.
Changed targets, stale buttons, mismatched tasks/providers/schemas, expiry, revoked grants,
competing spoken questions and failed writes cannot authorize effects. No native grant can
admit an unknown or consequential tool. Revocation pauses dependent future steps; completed
receipts and independent work remain intact.

At startup/foreground, unlocked, relevant native steps resume in order after authority,
schema, dependency and origin checks. The conservative engineering default is a two-minute
validity window for short phone plans; the general relevance planner is a later supervisor
dependency. Unknown effects and legacy unbound work are not replayed. A chat task panel
shows the exact target and current approval choices, cancellation and acknowledgement of
an inspected unknown result. Acknowledgement records reconciliation without granting retry.
Recovered task summaries and receipts project into their original chat. Retention keeps
256 recent completed attempts and protects unfinished/unknown groups within 512 attempts
and 1 MiB; full protected capacity still fails closed. Existing chat receipts survive pruning.

Acceptance: JVM tests cover ordered ownership, generation/racing approval claims, atomic
rollback, revised targets, framed fingerprints, denial, expiry/scope/revocation, cancellation,
restart, legacy migration and retention. Android `test31` exercises volume/battery and
approval denial with real Android executors; `test32` exercises the production panel with
controlled receipts and confirms no retry on reconciliation. The named release contract has
33 methods per variant. The existing phone-action and accepted-voice regressions remain
required. Hosted release/JVM, both emulator variants and consolidated receipt must verify
the exact candidate; no local Kotlin/Gradle/Android SDK exists. Real weights, physical
audio, actual process death during a side effect and device performance remain unverified.

M1b phone/media, M1c screen control, M1d task/conversation scheduling, M1e device validation,
M2 workflow UI/scheduling and A0–A6 resident/proactive behavior remain open. M1 as a whole
is not complete. PStack's coordinated Terra route remains unavailable on this host; no
independent actor review or coordinated provenance receipt is claimed.

### Retained Build 853 harness failure

Run [853](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36672387929) tested
`c199a6c`. Production Kotlin compiled; `compileReleaseAndroidTestKotlin` rejected new
`test32`'s `testTagsAsResourceId` use without `ExperimentalComposeUiApi` opt-in (line 1122).
The fixture now has the same explicit opt-in used by the existing controlled UI journeys.
This is a test compilation repair, with no acceptance removed or weakened. Failed run logs
and receipt artifact remain evidence; a new exact-revision full release run is required.

### Migration and question-presentation closure

Old unbound paused attempts are now visible and individually cancellable in the task
panel; cancellation persists without hiding an unresolved unknown effect. The JVM and
existing panel journey cover this migration edge. An approval request also remains inactive
for spoken yes until `presentQuestion` explicitly marks that exact question as presented;
creating a pending button alone cannot authorize a spoken choice. A focused JVM check
covers that boundary. These production corrections require a fresh complete release gate.

### Retained Build 854 shared-release-ABI failure

Run [854](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36674011110) at
`675b950` passed its signed build and JVM gate. Both API variants passed `test01`–`test31`,
then crashed in `test32` at its direct Compose `collectAsState` call with
`NoClassDefFoundError: androidx.compose.runtime.SnapshotStateKt`. This is the separate
release-test DEX/shared-app ABI boundary, not a failing native recovery effect. Both
device evidence archives were downloaded and their ZIP digests matched GitHub metadata.
Preserve the observed `SnapshotStateKt` facade/parts and its returned `State` interface,
matching the existing narrow Compose ABI rules. No scenario or assertion is removed.
Publication remains blocked until a new exact-revision gate passes.

## September 30 — M1a durable phone-action slice

Intake head: `6ce979733b89ff488f9a0c44a55260b8e4efccc6`. Existing application baseline
`bfeca6d` passed branch run [36179362325](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36179362325);
that historical result does not verify this change. Source refresh: `audio-pr2` at
`b54bb98bd1d0330d83a1ef381b0e7eb298a24863` (download fixture repair), Memory OS at
`20bbcfc2efac96242161a10c100b6a9632683786` (SQLite/migration). Neither source is imported
or advanced; this change touches only the action journal and its two shared runtime dispatch
sites, preserving the current engine/voice/memory implementations.

The next original M1a dependency is implemented before the newer autonomy phases:
bounded app-private atomic JSON attempts, generation-fenced transitions, typed receipts,
unknown-outcome recovery for running effects, and paused unfinished work. Disk commit must
succeed before dispatch. A failed receipt write reports unknown completion without retry.
The direct app fast path and shared text/voice model-directed actions use this journal.
Cancellation/programming errors retain their propagation and leave unknown evidence.

New focused JVM coverage and release journey `test30` are added without weakening existing
checks. Hosted exact-revision build, both emulator variants and receipt remain pending.
No local Android SDK/Gradle/Kotlin compiler is available. PStack's dependency planner was
used; its coordinated native receipt is unavailable because the requested Terra route is
not offered by this host. No actor/model provenance or independent-review receipt is claimed.

M1a is partial: durable authority, task groups/steps, safe automatic resumption, retention,
reconciliation UI and delivery projections remain dependencies. The journal caps at 512
attempts / 1 MiB and fails closed at capacity. M1b phone/media additions, M1c screen control,
M1d conversation/task controls, M2 workflows and A0–A6 autonomy are not complete.

## Baseline — 2026-09-24

The tools planning head was `dcbaa66bcdfed0a3fad0f21740345a4c9a1b3876`.
At implementation intake, both `audio-pr2` and `feature/memory-os-v2` pointed to
`e3a68a05f59bc32c91ec8329c75c2a1dd078b27f`. That common baseline is integrated once,
preserving the tools audit, interview decisions and implementation plan. Neither source
branch is advanced by this work. This brings in `754884f` (voice interruption,
streaming and persistent reply metrics) and `e3a68a0` (Compose test opt-in fix).

`feature-tools` is explicitly added to both Android APK push routing and its build
job condition. The existing build, API 30/API 35 sandbox, receipt and publication
dependencies remain required. At this baseline, branch CI did not create a PR or publish
a release; the September 30 publication correction supersedes that release behavior.

## Sources to refresh through the epic

| Milestone | Source to inspect | Integration responsibility |
| --- | --- | --- |
| M0 / M1 | `audio-pr2` | Accepted-action queue, interruption, streaming, reply metrics, native inference scheduling and voice lifecycle. Preserve speech-only stop and completed receipts; implement task lifetime beyond call end separately. |
| M1 / M2 | `audio-pr2` and `feature/memory-os-v2` | Shared JarvisRuntime, ConversationRuntime, ConversationScreen, durable history and UI lifecycle. Compare exact SHAs and ancestry before importing; avoid duplicate common commits. |
| M6 | `feature/memory-os-v2` | Memory store/wiki, correction and deletion lineage, retrieval fencing and idle scheduling. Existing manual proposal review is baseline behavior; global automatic self-review/scoring remains a new tools-epic requirement. |
| Every slice | Both source branches | Record fetched SHA, selected commits, conflicts/resolution, affected acceptance tests and exact combined CI run. Import relevant changes deliberately; never blanket overwrite runtime/UI or acceptance files. |

## First implementation slice

M0/M1a begins with the existing three-tool contract and enforceable model-pass limit.
Acceptance must cover schema/strict-decoder agreement, malformed arguments causing no
effects, the configured limit preventing extra inference, and preserved ordered calls,
receipts, explicit repeats, replay suppression, cancellation and failure stopping.
These are JVM logic checks; Android executors continue through the existing release
journeys. PStack companion coordination records the actual coding and separate review
actors. Exact remote CI evidence must be checked after publication.

This slice does not complete M1: new phone commands, approved screen control,
silently-working mode, durable task supervision and confirmation UI remain pending.
AppFunctions consumer access/discovery, MCP, conditional workflows, local scripts and
FunctionGemma are not enabled by this baseline. No Android Control MCP source is
ported yet; retain its license and author credit if later slices reuse its code.

## M0/M1a contract slice — pending exact CI

The existing native actions now share `MobileToolCatalog`: the catalog declares each
stable name, version, description and typed parameter once, then generates the LiteRT
schema and validates the strict side-effect decoder. `open_app` now has only the
required human-readable `app` string; the unsupported package schema is removed.
`set_volume.level` is a genuine JSON integer from 0 through 100. Numeric strings,
decimals, nulls and extra fields are rejected at the strict boundary. The established
sole `{ "args": { ... } }` wrapper remains accepted there. Legacy `decode` intentionally
remains tolerant for existing fixtures and compatibility paths.

LiteRT automatic tool calling remains disabled. Its SDK callback reports disabled
execution rather than returning a fake successful `{}` response; validated runtime
dispatch remains the only Android side-effect path.

`ActionTurnRunner` now rejects non-positive configured model-pass budgets, counts the
initial generated call batch as pass one, and never requests another model batch after
the configured final pass. `ExecutionResult` preserves its existing `(Boolean, String)`
JVM constructor and adds typed success, ordinary failure, validation rejection,
permission denial and unknown-completion outcomes. Unknown completion is not retried.

New JVM checks are pending hosted exact-SHA CI: catalog/schema parity; strict
type/range rejection with zero executor effects; retained tolerant decode; callback
failure closure; one/two-pass limits with no extra inference; completion within one
batch; invalid budgets; and distinct denied/unknown/validation outcomes. Existing
ordered replay, explicit-repeat and cancellation coverage is retained. M1 commands,
screen control, workflows and release journeys remain pending and no Android or
real-model claim is made by this slice.

## Source refresh during M0/M1a

`audio-pr2` remains at `e3a68a05f59bc32c91ec8329c75c2a1dd078b27f`.
`feature/memory-os-v2` advanced to
`5211b8f246c6bd441c08499f68320d013d8f88bd` (`Add dedicated Memory wiki with review,
indexing, search and lifecycle repairs`). That source is not imported into this bounded
action-contract slice. Revisit its wiki category/topic/source/link/index/history and
navigation changes at M6 and the shared UI boundary, after checking that source's CI
and the combined-revision tests.

## Conditional/multiple phone-action repair — 2026-09-30

Starting head: `5b2db514ca416d9b32eface711a2ffee5cdba946` on `feature-tools`.
Preserved the newer M1a durable task/approval code beyond Build 851. Immediate current
battery conditions and comma-separated plans now use direct validated text/final-voice
execution, typed numeric Android readings, durable step receipts, stop-on-failure and
restart fencing for conditional groups. General workflows/schedulers and new tools
remain outside this repair. Added ConditionalActionPlanTest and release test33; updated
the named scenario contract without removing existing checks.

The retained Build 857 API 30 artifact (11080861694, run 36676486374) shows test32's
visible task-dialog buttons lacked exported resource IDs. Enabled resource-tag semantics
inside the production dialog root. Both failed emulator jobs remain historical evidence;
this correction requires the new exact-head full gate. Focused local Kotlin/JUnit checks
and Python helper checks pass; hosted Android evidence is pending at commit intake.

PStack Work Port planning/evidence tools were used. Full Terra companion coordination
is unavailable on this host, so no independent actor/model review receipt is claimed.
