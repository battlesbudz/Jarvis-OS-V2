# Native MemoryOS milestone

This milestone ports the reviewable local ledger from the original Jarvis OS MemoryOS work into the Android app. The upstream reference is [jarvis-os commit d8018e4b4ce263a9d03aef41cb864a66e45e331d](https://github.com/battlesbudz/jarvis-os/commit/d8018e4b4ce263a9d03aef41cb864a66e45e331d); the native branch is based on `audio-pr2` at `c20b5137d010aa2a99814bea4387201b485182c7`. The combined feature line is `feature/memory-os-v2`; it merges the build-768 conversation line with the existing audio/MemoryOS integration. APK and build status come only from the exact combined run receipt.

The feature content is combined with the pinned natural-routing audio snapshot `9b133b466cf8508618778ea41c25aefd36910737` (including audio snapshots `9ccaca5`, `d37eb0c`, `5643517`, and `9b133b4`) by a single-parent content import; it is not a Git merge.

## What is implemented

The native API is in `app/src/main/java/com/battlesbudz/jarvis/v2/memory/`:

* `MemoryModels.kt` defines bounded records, source/provenance, review state, outcomes, snapshots and packet results.
* `MemoryPolicy.kt` validates content, timestamps, confidence, provenance and restricted data. It uses conservative detectors for raw financial or identity data; it is not a blanket secret detector.
* `MemoryStore.kt` stores schema-versioned JSON with an atomic write, a per-file process lock, cleanup of store-owned interrupted-write artifacts on access, a 1 MiB encoded-store cap, record/tombstone caps, and fail-closed reads.
* `MemoryOs.kt` exposes `propose`, `approve`, `reject`, correction supersession, lineage deletion, generation/revision checks, listing, retrieval and bounded context packets.
* `MemoryRetrieval.kt` performs deterministic lexical token matching and emits JSON-quoted historical entries under a character budget.
* `AndroidMemoryOs.kt` binds the store to the app's `noBackupFilesDir/memory-os.json`; it uses no network and adds no model.

The lifecycle is explicit: a proposal is `PENDING`, a user can approve or reject it, and an approved correction supersedes its active target. A correction records `correctsMemoryId` and checks the target revision. Erasing a record erases its complete correction lineage and retains only opaque event tombstones for idempotency; content is not retained in the tombstone. Reused event IDs with different payloads are conflicts. Storage, schema, capacity, stale revision and unavailable-store failures are returned as outcomes/messages for callers to show.

`MemoryScreen.kt` is opened from the setup/model screen's **Memory** button. Its **Add** action opens a modal, and the screen separates Wiki, Review, and History. It supports add-for-review, approve, reject, correct, lexical search over approved records, category/topic placement, source inspection, derived links/backlinks, per-record erase, confirmed erase-all, and reload after Activity recreation. Finalized text and voice inputs enter the same conservative pending-review path; they are not approved or injected automatically. Pending, rejected, superseded, deleted, and expired records are absent from the wiki. Approved recall is local, quoted historical context with no tool authority. The restricted example in the release journey must remain rejected, and storage errors must remain visible instead of looking like an empty history. See `docs/memory-wiki.md` for the user-facing verification recipe.

## Retrieval and trust boundary

Only approved, non-expired records can match retrieval. A query must be non-blank and its limit must be 1–50. Matching is lexical and deterministic; confidence, then recency, then ID break equal lexical scores. Narrow deterministic patterns propose explicit remembers, preferences, names, and locations for review. There are no embeddings, semantic similarity, cloud retrieval, or model-based extraction in this milestone.

`contextPacket` applies a caller-supplied character budget and wraps each selected value in JSON quoting inside a clear historical-data header. The packet is a bounded model-context artifact. It is not an instruction, tool request, authorization, current-user message, or source of truth. Any future integration must route the raw current user request through action selection first, then add an approved packet only as untrusted historical model context. It must never let memory authorize a tool or override system, developer, safety, tool, or current-user instructions.

The current candidate exercises the finalized-input bridge and prompt builder through controlled acceptance surfaces. Integration must route the raw current request through action selection first, then add only the approved packet as untrusted historical context. Personal recall is local and expires with the approved record; expiry, correction, and erase must invalidate a seeded packet, as must model switching and retrieval/setup failures, with an explicit result. Memory must never authorize a tool. These paragraphs define the acceptance boundary; completion requires the exact combined-tree run receipt and review.

## Port parity and scope

The parity reference is the original server modules:

| Original module | Native counterpart | Native milestone boundary |
| --- | --- | --- |
| `server/memory/memoryOs.ts` | `MemoryOs.kt`, `MemoryModels.kt` | Local lifecycle and result contracts; no server callers or user account |
| `server/memory/writePipeline.ts` | `MemoryOs.kt`, `MemoryPolicy.kt`, finalized-input bridge | Explicit text/voice proposal and review path; no inferred/working/dream auto-approval |
| `server/memory/trust.ts` | `MemoryModels.kt`, `MemoryPolicy.kt`, packet header | Provenance and untrusted context shape; no cloud trust graph |
| `server/memory/retrieve.ts` | `MemoryRetrieval.kt` | Bounded lexical retrieval only; no embeddings or pgvector |
| `server/memory/contextBuilder.ts` | `MemoryRetrieval.kt` | Quoted bounded packet only; runtime prompt integration with mutation/expiry delivery fences |
| `server/memory/restrictedContent.ts` | `MemoryPolicy.kt` | Conservative local restricted-content checks |

Cloud embeddings/pgvector, G-Brain, extraction, people/SOUL/vault/dreaming, automatic review and multi-user sync are outside the port. Redis and Graphiti remain upstream plans. The upstream `rememberEpisode` gap is not silently dropped from an existing native feature: it has no native implementation in this milestone.

## Verification contract

Focused JVM coverage is present in:

* `MemoryPolicyTest`: restricted content/provenance, bounds and opaque source keys.
* `MemoryStoreTest`: reopen/commit behavior, failed writes, corruption and unknown schema.
* `MemoryOsTest`: source idempotency/conflicts, concurrent store instances, stale review and lineage deletion.
* `MemoryRetrievalTest`: lexical/expiry/review filtering and delimiter/injection-safe packets.

The release journeys are `test24_memoryManagerReviewsCorrectsSearchesAndErases`, `test25_finalizedTextAndVoiceMemoryNeedsApprovalBeforePromptUse`, `test26_memoryCorrectionAndEraseRefreshApprovedPacket`, and `test27_controlledConversationSurfaceKeepsCallUntilExplicitEnd`, listed in `scripts/verification/scenarios.json`. Together they describe the real Memory modal/review/wiki lifecycle, controlled finalized text/voice proposals that require an actual Review approval tap, category/topic organization, linked pages/backlinks, source inspection, rejected/pending search exclusion, correction/erase index invalidation, recreation persistence, quoted approved local recall, bounded typed follow-ups, and retaining one call across Voice, Chat and Memory until explicit end. They use controlled fixtures. Build 768 passed 685 JVM tests and 28 named journeys per emulator variant; the merged revision requires fresh CI. Activity recreation is UI persistence coverage; Android process death has not been tested here.

The combined revision requires code review and the full signed release gate: JVM, native/helper checks, normal API 30 sandbox, compact API 35 sandbox, and the consolidated exact-build receipt. Use the exact combined-tree run receipt for APK/build status. Do not infer microphone capture, ASR, TTS, model weights, physical audio, or device performance from these controlled journeys.

## Memory Conversations integration

The merge preserves both parents: MemoryOS/audio `7a72dfe1fe0577f94e9da580af9b0020f4173771` and Memory Conversations build 768 `649f58cfb5fd03197cf760db1ddf1d8fbc39dcde`. Only `feature/memory-os-v2` advances. The existing PR and other branches remain untouched.

The conversation implementation supplies finalized-input capture, one approved snapshot per ordinary turn, persisted history cutoffs, mutation/expiry delivery fences, persistent calls, and bounded typed/spoken follow-ups. The prior branch's reviewed local-personal-recall behavior is retained: matching approved personal facts bypass automatic references; explicit lookup and lookup confirmation retain references. Phone actions are authorized from the raw current request and receive no memory packet. An invalidated incremental input is closed and excluded from generation.

`test28_voiceNavigationRetainsCallIdUntilExplicitEnd` preserves the prior branch's navigation contract in addition to the stronger controlled production surface journey `test27`. The merged contract contains 29 journeys per emulator variant. `MemoryRecallIntegrationTest` verifies pending/approved/erased routing and explicit lookup/action precedence. Fresh combined-commit CI is required; historical parent passes are not combined-revision evidence.
