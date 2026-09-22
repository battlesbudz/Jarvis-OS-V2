# Native MemoryOS milestone

This milestone ports the reviewable local ledger from the original Jarvis OS MemoryOS work into the Android app. The upstream reference is [jarvis-os commit d8018e4b4ce263a9d03aef41cb864a66e45e331d](https://github.com/battlesbudz/jarvis-os/commit/d8018e4b4ce263a9d03aef41cb864a66e45e331d); the native branch is based on `audio-pr2` at `c20b5137d010aa2a99814bea4387201b485182c7`. It is a standalone milestone and does not claim that chat or voice recall is live.

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

The UI is deliberately manual. `MemoryScreen.kt` is opened from the setup/model screen's **Memory** button. It supports add-for-review, approve, reject, correct, lexical search over approved records, per-record erase, confirmed erase-all, and reload after Activity recreation. The UI states that it does not automatically save chat or voice conversations and that conversation recall is not connected. The restricted example in the release journey must remain rejected, and storage errors must remain visible instead of looking like an empty list.

## Retrieval and trust boundary

Only approved, non-expired records can match retrieval. A query must be non-blank and its limit must be 1–50. Matching is lexical and deterministic; confidence, then recency, then ID break equal lexical scores. There are no embeddings, semantic similarity, cloud retrieval, or automatic extraction in this milestone.

`contextPacket` applies a caller-supplied character budget and wraps each selected value in JSON quoting inside a clear historical-data header. The packet is a bounded model-context artifact. It is not an instruction, tool request, authorization, current-user message, or source of truth. Any future integration must route the raw current user request through action selection first, then add an approved packet only as untrusted historical model context. It must never let memory authorize a tool or override system, developer, safety, tool, or current-user instructions.

The designated integration chat owns the deferred hook in `ConversationRuntime` and `ConversationPromptBuilder`. That work must invalidate a seeded model context when a memory is deleted or corrected, when the selected model changes, or when retrieval/setup fails; failures must be explicit. Automatic chat/voice capture and memory injection are not implemented here.

## Port parity and scope

The parity reference is the original server modules:

| Original module | Native counterpart | Native milestone boundary |
| --- | --- | --- |
| `server/memory/memoryOs.ts` | `MemoryOs.kt`, `MemoryModels.kt` | Local lifecycle and result contracts; no server callers or user account |
| `server/memory/writePipeline.ts` | `MemoryOs.kt`, `MemoryPolicy.kt` | Explicit manual proposal/review path; no inferred/working/dream writes |
| `server/memory/trust.ts` | `MemoryModels.kt`, `MemoryPolicy.kt`, packet header | Provenance and untrusted context shape; no cloud trust graph |
| `server/memory/retrieve.ts` | `MemoryRetrieval.kt` | Bounded lexical retrieval only; no embeddings or pgvector |
| `server/memory/contextBuilder.ts` | `MemoryRetrieval.kt` | Quoted bounded packet only; no prompt/runtime integration |
| `server/memory/restrictedContent.ts` | `MemoryPolicy.kt` | Conservative local restricted-content checks |

Cloud embeddings/pgvector, G-Brain, extraction, people/SOUL/vault/dreaming, automatic review and multi-user sync are outside the port. Redis and Graphiti remain upstream plans. The upstream `rememberEpisode` gap is not silently dropped from an existing native feature: it has no native implementation in this milestone.

## Verification contract

Focused JVM coverage is present in:

* `MemoryPolicyTest`: restricted content/provenance, bounds and opaque source keys.
* `MemoryStoreTest`: reopen/commit behavior, failed writes, corruption and unknown schema.
* `MemoryOsTest`: source idempotency/conflicts, concurrent store instances, stale review and lineage deletion.
* `MemoryRetrievalTest`: lexical/expiry/review filtering and delimiter/injection-safe packets.

The release UI journey is `ReleaseJourneyTest.test20_memoryManagerReviewsCorrectsSearchesAndErases`, listed in `scripts/verification/scenarios.json`. It covers restricted-input rejection, add/approve, search, correction approval and exclusion of the superseded fact, rejection, erase-all cancellation/confirmation, and persistence after Activity recreation. Existing scenarios remain unchanged. Activity recreation is UI persistence coverage; Android process death has not been tested here.

Before integration, the combined revision requires code review and the full signed release gate: JVM, native/helper checks, normal API 30 sandbox, compact API 35 sandbox, and the consolidated exact-build receipt. The combined feature revision is a release candidate only after its exact signed normal/compact gate, both emulator journeys, and consolidated receipt complete; this document does not claim that evidence yet. Real model inference, voice capture, automatic recall and physical performance remain unverified.

## Integration file ownership

The integration chat should review the exact source files before wiring the deferred runtime hook:

* `app/src/main/java/com/battlesbudz/jarvis/v2/conversation/ConversationRuntime.kt`
* `app/src/main/java/com/battlesbudz/jarvis/v2/ai/ConversationPromptBuilder.kt`
* `app/src/main/java/com/battlesbudz/jarvis/v2/ui/JarvisApp.kt`
* `app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt`
* `scripts/verification/scenarios.json`
* `docs/verification/features.md`

The memory implementation and UI are new files for this milestone. `JarvisApp.kt`, `ReleaseJourneyTest.kt`, `scenarios.json` and this feature map are shared overlap points; preserve the other chat's audio-pr2 and multi-action changes when integrating. No PR, merge or release push is implied by this document.
