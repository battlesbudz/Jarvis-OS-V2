# Tools epic integration log

Epic: [#8](https://github.com/battlesbudz/Jarvis-OS-V2/issues/8). Implementation branch: `feature-tools`.

## Baseline — 2026-09-24

The tools planning head was `dcbaa66bcdfed0a3fad0f21740345a4c9a1b3876`.
At implementation intake, both `audio-pr2` and `feature/memory-os-v2` pointed to
`e3a68a05f59bc32c91ec8329c75c2a1dd078b27f`. That common baseline is integrated once,
preserving the tools audit, interview decisions and implementation plan. Neither source
branch is advanced by this work. This brings in `754884f` (voice interruption,
streaming and persistent reply metrics) and `e3a68a0` (Compose test opt-in fix).

`feature-tools` is explicitly added to both Android APK push routing and its build
job condition. The existing build, API 30/API 35 sandbox, receipt and publication
dependencies remain required. Branch CI does not create a PR or publish a release.

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
