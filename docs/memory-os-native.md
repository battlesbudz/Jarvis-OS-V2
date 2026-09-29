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

## Plan to finish MemoryOS: local semantic and autonomous recall

**Status:** planned follow-on work; the sections above describe the implemented native milestone. The current lexical path remains the working fallback until this plan is implemented.

**Goal:** approved memories should be recalled during ordinary Chat and Voice turns when they are relevant, including paraphrased or implied references. Users should not have to say “remember” or explicitly request a memory search for relevant context to be available.

### 1. Qualify the local embedding runtime

Use **EmbeddingGemma 300M** as the first Android candidate. Google's current Text Embedder documentation supports the EmbeddingGemma 300M task artifact on Android through MediaPipe Tasks Text, including `TextFormatContext` for retrieval query/document roles. Use the app's Kotlin integration and a pinned, reviewed model artifact; do not send memory text or queries to a hosted embedding service.

The model overview describes a 308M multilingual model, 768-to-128 output dimensions, and on-device/offline use. The Android task artifact has a 512-token sequence limit, even though the general model overview describes a longer context; verify tokenization and chunking against the exact artifact. Benchmark 768- and smaller-dimensional output, plus float and scalar-quantized vectors, against the same recall suite before choosing the stored representation. Precompute document vectors when a memory becomes eligible; only the current query needs to be embedded on a normal turn.

Check warm and cold inference, peak memory, model storage/download size, battery, and voice-turn latency on the target Galaxy Z Fold 6. Google's published Text Embedder table reports 200 ms CPU latency on a Samsung S26 Ultra, which is a reference point, not a Fold 6 result. Choose whether the artifact is bundled or explicitly acquired once on device after measuring APK/storage impact; the resulting recall path must work offline. Audit dependency telemetry/network behavior and confirm no memory or query text leaves the device. Review Gemma Terms before commercial distribution. If EmbeddingGemma fails the device or licensing gates, benchmark BGE-small-en-v1.5 with ONNX Runtime Mobile as the English-only fallback; it needs its own tokenizer/pooling integration and quality test.

### 2. Add a rebuildable local hybrid index

Keep `memory-os.json` as the authoritative, inspectable ledger. Add a private local SQLite sidecar for retrieval fields, lexical search, and embedding blobs; treat the sidecar as derived data, never as the only copy of a memory. Begin with app-side cosine scoring over the bounded local corpus; add an approximate-nearest-neighbor index only if measurements show it is needed.

Index only approved, active, non-expired memories. A vector row must be tied to the memory ID, ledger revision/content hash, embedding model and version, task-format/template version, and output dimension. Use the documented `RETRIEVAL_DOCUMENT` form when embedding a memory and `RETRIEVAL_QUERY` form for a user query; preserve the title/category and relevant time/relationship metadata in the document text. Keep the index in the same private local storage boundary as the ledger.

Build deterministic index creation, schema migration, and full rebuild from the ledger. Approval adds a vector; rejection never does. Correction/supersession, expiry, erase, and erase-all must invalidate stale vectors and already-built packets using the existing generation/delivery fences. If an index is missing or corrupt, rebuild it or fall back to the lexical path with an explicit retrieval status; never silently treat an index failure as “no memories.”

### 3. Retrieve autonomously on ordinary turns

Run memory retrieval for every normal conversational turn, not only when the utterance contains a recall phrase. First route the raw current request through existing action selection and authorization. Then form a retrieval query from the current utterance and a bounded recent-conversation window so paraphrases, pronouns, and references like “that place we discussed” can resolve. Do not let retrieved memory change Kotlin tool authorization or give a tool permission.

Use hybrid candidate generation: preserve deterministic exact/lexical matching for names, dates, and rare terms, and add dense cosine similarity for semantic matches. Combine ranks rather than adding uncalibrated scores. Rank or rerank with type/topic/person overlap, confidence/provenance, recency, and temporal validity; a newer memory must not override an older fact that is still valid for the question's time. Apply approval, expiry, deletion, and supersession filters before ranking. Inject only a small, relevance-thresholded set into the bounded, JSON-quoted historical context packet with source, recorded time, and applicable validity. If nothing clears the threshold, send no memory context. Preserve explicit lookup/reference behavior and the current raw-request-first trust boundary.

### 4. Complete memory lifecycle and temporal meaning

Keep capture and recall as separate paths. Extend local proposal generation beyond the current narrow deterministic “remember/preference” patterns, but keep inferred proposals pending user review unless a later, separately approved policy explicitly changes that rule. Index only after approval. Maintain the Wiki, source traceability, corrections, rejection, expiry, and deletion behavior.

Add typed temporal and relationship fields needed to answer questions accurately: memory kind (identity/preference, episode, person/relationship, goal/plan, or procedure), subject/topic links, source and confidence, recorded time, valid-from/valid-until, and supersession/correction lineage. Preserve the distinction between when Jarvis learned something and when it was true. After autonomous recall passes its quality gates, add local consolidation (deduplication, summarization, promotion/demotion, decay, and charging/idle-time “dream” work) as a separate milestone. Consolidated facts remain traceable to source episodes and enter the same review path; consolidation must not silently approve or erase user memories.

### 5. Prove quality and lifecycle safety

Create a fixed local golden set with direct asks, paraphrases, implied references, pronouns/coreference, temporal questions, exact-name/date lookups, competing or corrected facts, and cases with no relevant memory. Compare hybrid retrieval with the existing lexical-only baseline. Record Recall@5, ranking quality, and irrelevant-memory injection rate, then set release thresholds against that fixed set. Require zero policy failures: pending/rejected/expired/superseded/erased memories never appear, corrections suppress stale values, and memory never authorizes an action. Add tests for index rebuild/migration, model-version changes, model-unavailable fallback, and delivery-fence invalidation after a memory mutation.

Run offline/network-egress checks and measure cold-start and warm per-turn latency, peak RAM, storage/APK impact, and battery on the Fold 6. Keep emulator API 30 and API 35 journeys for lifecycle and integration coverage, but do not treat emulator numbers as physical-device performance evidence. Ship semantic recall only when quality beats the lexical-only baseline on implicit/paraphrased cases without increasing irrelevant injections and the target-device gates pass.

### Alignment with Jarvis V1 G-Brain and Temporal Graph

This plan follows V1's retrieval and trust contracts, adapted to Android-local storage. It does not copy V1's server stack or claim that V1's planned temporal graph was already implemented.

**G-Brain reference and V1 implementation**

Garry Tan's upstream [GBrain](https://github.com/garrytan/gbrain) is a markdown/page-centered memory system: pages hold current compiled truth and evidence timelines; typed links form a knowledge graph; search combines vector and keyword rankings with reciprocal-rank fusion (RRF), and can traverse graph edges for relationship questions. The upstream project is a design reference, not an Android dependency.

Jarvis V1 implemented its own `server/brain/*` projection layer. Its [G-Brain implementation plan](https://github.com/battlesbudz/jarvis-os/blob/main/docs/gbrain-implementation-plan.md) records the landed Postgres projection tables and verified behavior: canonical memory remains authoritative; approved memories and people project into rebuildable pages, chunks, links, and page versions; canonical and G-Brain candidates fuse with RRF; provenance and fallback status are retained. V1 used hosted OpenAI embeddings and optional pgvector. V2 replaces that runtime with the local EmbeddingGemma/index path above while preserving the source-versus-derived boundary and hybrid retrieval behavior.

Therefore, expand the V2 sidecar from a vector cache into a rebuildable G-Brain-style projection:
- pages for approved memories and linked people/topics, each carrying canonical source IDs, review state, and provenance;
- chunks with lexical index and local vectors;
- explicit typed links/backlinks;
- timeline entries for dated episodes and changing facts;
- append-only derived page versions tied to source revisions.

Retrieve canonical ledger records and projected pages as peer candidate sources. Use lexical and vector arms, then page-level RRF/deduplication; when a page points to a canonical memory, the canonical record remains authoritative and the selected result retains chunk/page provenance. Use stored typed links for relationship questions. Create links only from explicit approved relations or deterministic, reviewable evidence; similarity alone must not invent a relationship.

**Temporal Graph status and V2 port**

Jarvis V1's [Temporal Graph plan](https://github.com/battlesbudz/jarvis-os/blob/main/docs/memory-os-temporal-graph-plan.md) distinguishes time parsing, hot state, semantic recall, and a graph of changing entities/facts. It explicitly lists the Graphiti adapter and temporal query UX as later work. V1's `server/time/temporalContext.ts` resolves expressions such as “last month” into user-local time windows; that parser does not itself store or traverse a temporal knowledge graph.

Keep the temporal layer separate from vector similarity. After the local G-Brain projection and hybrid recall work, add subject–predicate–object edges and event timeline records with both **observed/recorded time** and **valid time**, source IDs, confidence, and supersession. Support point-in-time questions (“what was true then?”), current-versus-past comparisons, and relationship changes with provenance. Reuse the temporal parser for query windows.

Treat Graphiti as the V1 design target to evaluate, not as a completed V1 port or an assumed Android dependency. Add a proof-of-fit for an offline, on-device Graphiti deployment. If it cannot meet the no-network, storage, lifecycle, and Fold 6 performance constraints, implement the same narrow temporal-graph contract over the local SQLite projection. The JSON ledger remains the canonical truth either way; graph/index maintenance must be rebuildable and corrections/expiry/erasure must remove stale derived facts. Run consolidation as opportunistic Android background work while idle/charging, not as a server cron, and keep generated summaries reviewable and source-linked.

**Research references**

- [EmbeddingGemma model overview](https://ai.google.dev/gemma/docs/embeddinggemma)
- [MediaPipe Text Embedder guide](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder)
- [EmbeddingGemma Android task artifact](https://huggingface.co/litert-community/embeddinggemma-300m)
- [BGE-small-en-v1.5 model card](https://huggingface.co/BAAI/bge-small-en-v1.5)
- [ONNX Runtime Mobile](https://onnxruntime.ai/docs/get-started/with-mobile.html)

