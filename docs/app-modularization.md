# App modularization and handoff map

Current maintainability pass: `audio-pr2`, 2026-10-02. The app remains one Gradle
application module with responsibility-focused packages and explicit collaborators;
see [ADR 001](architecture/adr-001-package-boundaries.md). Start with the
[architecture](architecture/README.md), [change guide](architecture/change-guide.md),
and [development setup](architecture/development.md) for newcomer navigation.

## Current owners

Paths are relative to `app/src/main/java/com/battlesbudz/jarvis/v2/`.

| Area | Owner | Boundary |
| --- | --- | --- |
| Android entry points | `MainActivity`, `voice/VoiceCallService`, `assistant/` services | Permissions/results, UI attachment, service eligibility and platform lifecycle |
| App composition | `ui/JarvisApp`, screen files | Routing and retained conversation/voice/memory surfaces; public Compose/test boundary preserved |
| Setup operations | `presentation/ModelSetupOperations`, `ModelSetupContract` | Activity-scoped select/delete/import/smoke work via a narrow session port; download lifetime in WorkManager |
| Setup presentation | `ui/ModelSetupState`, `ModelSelectionSection` | Observed readiness/work identity, explicit setup callbacks and shared browser/storage/access controls |
| Process/call coordinator | `JarvisRuntime` | Single shared native/conversation owner, capture and ordinary answer lifecycle, accepted follow-up pump |
| Phone task coordinator | `runtime/PhoneTaskCoordinator` | Durable journal admission, execution, decisions, unlock/restart recovery and conversation receipt projection |
| Memory coordinator | `runtime/RuntimeMemoryCoordinator` | Epoch/cutoff/token ordering, fresh context and one-use capture receipts; native reset remains runtime-owned |
| Accepted action coordinator | `runtime/AcceptedVoiceActionCoordinator` | Process-lifetime serial queue, generation-safe model lease and saved-ID terminal reporting |
| Runtime audio assembly | `runtime/RuntimeVoiceResources`, `VoiceTurnCaptureFactory`, `VoiceTurnOutputFactory` | Routing/capture/model assembly, frozen capture configuration, output/echo/evidence callbacks |
| Final voice interpretation | `runtime/FinalVoiceInputResolver` | Final ASR and bounded, tool-disabled Gemma audio fallback |
| Voice turn evidence | `runtime/VoiceTurnTelemetry` | Capture, synthesis, actual playback and live measurement projection |
| Conversation coordinator | `conversation/ConversationRuntime` | Shared admission/coroutine lifetime, generation filters, bounded answer repairs, final response and cleanup |
| Turn routing/context | `conversation/ConversationRouting`, `ConversationContext` | Action/status/integrity route, memory fence/cutoff/recall/capture and reference context |
| Prompt/model ownership | `conversation/ConversationPrompt`, `ConversationModelSession` | Prompt budgets/compaction and native conversation reuse/capability decisions |
| Turn input | `conversation/ConversationInput` | Typed multimodal generation selection, attachment reads and incremental fallback |
| Turn action bridge | `conversation/ConversationActions` | One turn's journal group and Android receipt callbacks through explicit executor/journal ports |
| Turn reply | `conversation/ConversationReply` | Memory-valid publication, latency and benchmark finalization |
| Model repository | `ai/ModelStore`, `ai/storage/` | Catalog selection/install/integrity; transport/provider lookup/hashing have independent owners |
| Call audio resources | `voice/VoiceCallResources`, `VoiceModelSession`, `CallModelSlot` | Serialized microphone handoff, replay boundaries and native borrowers/owner dispatchers |
| Incremental input | `voice/IncrementalVoiceInput`, `ai/LiteRtVoicePrefillSession`, `GemmaSessionText` | Stable text, prefill/decode lifetime and native channel filtering; no action authority |
| Piper producer/playback | `voice/PiperVoiceOutput`, `PiperSpeechSynthesizer`, `SynthesizedSpeechPcm`, `SpeechAudioTrackFactory` | Output coordinates the existing bounded queue/ledger; helpers own native synthesis, PCM validation and track construction/release |
| Transcript/formatting | `ChatEntry`, `chat/` | Persistent threads, delivered context and shared display/speech formatting |
| Feature contracts/storage | `actions/`, `memory/`, `diagnostics/` | Typed authority, memory/source storage, benchmark evidence; no activity ownership |

## What this pass preserves

Single conversation admission, native lease/join ordering, microphone handoff,
accepted action lifetime, strict receipt-based action reporting, memory mutation
fences, persisted identities/schemas and the public UI/test ABI remain the contracts.
No alternate action executor, model backend, voice tuning, storage migration or
retired-voice revival is introduced by decomposition.

The entry point now delegates setup operations through a small session interface.
Conversation work names its routing, context, prompt, model, input, actions and
reply phases. Runtime journal/memory/accepted-action state belongs to focused
collaborators. Piper retains native ownership and delivery ordering while extracting
synthesis/PCM/track responsibilities. README, contribution guidance, architecture,
source map and environment/helper commands supply the handoff path.

Verification and current acceptance are recorded in
[the refactor contract](verification/modular-refactor.md) and
[the feature map](verification/features.md). A code extraction or historical pass
is not evidence that the new combined APK passed its exact-revision gate.

## Remaining coordination

`JarvisRuntime.runVoiceTurn` still owns the integrated capture/ordinary-answer and
continuous accepted follow-up state machine. `ConversationRuntime` still owns
streaming generation, final assembly and bounded factuality/repetition repair.
`AudioTurnCapture` retains its tightly coupled acoustic state machine with existing
policies/adapters. These are substantial coordinators, not claims of completed
physical audio/model acceptance.

Further extraction should introduce typed turn stages with one owner and explicit
inputs/results, preserve cancellation/cleanup timing, and demonstrate reduced
coupling. Avoid dividing a coroutine across unrestricted runtime extension files.
Independently built JVM/Android libraries remain conditional on the criteria in
[ADR 001](architecture/adr-001-package-boundaries.md).

## Earlier refactor checkpoint: 2026-09-16

The preceding pass replaced `JarvisScreens.kt` with focused screens, extracted
model transport/provider lookup/hashing, encapsulated microphone/model/replay
ownership in `VoiceCallResources`, moved shared transcript/admission policy out
of the activity, and consolidated display/speech filters in `AssistantText`.
That checkpoint's line counts and earlier test/build evidence described its
revision; the map above describes the current owners.
