# App modularization and review map

First pass on `audio-pr2` / existing PR #6, 16 September 2026. This is a staged
refactor of the current app, not a claim that every monolithic workflow is solved.
The app remains one Gradle application module. Focused Kotlin components establish
boundaries before introducing independently built libraries or changing lifecycle.

## Component ownership

| Area | Owner / files | Boundary |
| --- | --- | --- |
| Android activity | `MainActivity.kt` | Activity results, permissions, setup actions and UI attachment |
| Application runtime | `JarvisRuntime.kt` | Foreground voice session lifecycle and turn coordination |
| App navigation/setup state | `ui/JarvisApp.kt` | Model selection and choosing setup, call or history screen |
| Active call UI | `ui/VoiceCallScreen.kt` | Observe runtime state, issue user controls, show settings |
| Setup and history UI | `ui/ModelSetup.kt`, `VoiceCallsScreen.kt`, `VoiceCallDetailScreen.kt` | One screen responsibility per file; callbacks own actions |
| Model repository | `ai/ModelStore.kt` | Selection, exclusive setup ownership, integrity state and atomic installation |
| Model transfer | `ai/storage/ModelDownloader.kt` | HTTP ranges, temporary chunks and byte progress; no preferences or UI |
| Downloads lookup | `ai/storage/DownloadedModelLookup.kt` | Android exact-filename provider queries; no model installation |
| Model hashing | `ai/storage/ModelFileHash.kt` | Streaming SHA-256 and bounded progress callbacks |
| Call audio resources | `voice/VoiceCallResources.kt` | Serialized microphone ownership, replay boundary and resident model identity |
| Native resource leases | `voice/VoiceModelSession.kt`, `CallModelSlot.kt` | Native borrowers, owner dispatchers and release after borrowers join |
| Incremental voice input | `voice/IncrementalVoiceInput.kt`, `ai/LiteRtVoicePrefillSession.kt`, `ai/GemmaSessionText.kt` | ASR text stability, native prefill/decode lifecycle and raw-channel filtering; no tool execution or playback permission |
| Conversation admission and policy | `conversation/ConversationWork.kt`, `ConversationPolicy.kt` | Shared job admission and budgets; no dependency on the activity |
| Shared transcript / formatting | `ChatEntry.kt`, `chat/AssistantText.kt` | Transcript value type and display/speech formatting, independent of Android activity |
| Playback | `voice/PiperVoiceOutput.kt` and existing queue/ledger/clock helpers | PCM generation, delivery, cancellation and playback evidence |
| Diagnostics | `diagnostics/`, ASR/TTS stores and size-report scripts | Evidence collection; does not choose runtime behavior |

## Completed in this pass

- Replaced the 793-line `JarvisScreens.kt` with five focused screen files.
- Reduced `ModelStore.kt` from 712 to 350 lines by extracting transport, provider
  lookup and hashing. Existing filenames, preference keys, catalog validation,
  import rules and setup ownership remain unchanged.
- Encapsulated microphone/model ownership and the follow-up replay boundary in
  `VoiceCallResources`; kept the same serialized close/borrow behavior and native
  cleanup ordering.
- Moved shared transcript data, conversation admission and budget constants out
  of `MainActivity`. Conversation processing no longer reaches into the activity
  for its runtime policy or active-job counter.
- Consolidated duplicated display/speech text filters into `AssistantText`.
- Added HTTP resume/fallback and hash checks alongside existing voice regression
  coverage. UI extraction is verified by the Android release compilation gate.

## Remaining app-wide sequence

1. **Turn orchestration:** `JarvisRuntime.runVoiceTurn` still coordinates too many
   steps. Extract capture/recognition results, reply execution and finalization
   using typed inputs/results. Preserve cancellation propagation and microphone
   handoff; avoid exporting all runtime internals to extension files.
2. **Conversation execution:** split `ConversationRuntime.kt` into prompt/context
   preparation, model execution and validated tool dispatch. Keep one admission
   owner and one place that commits transcript/context.
3. **Piper output:** separate the native producer and AudioTrack consumer behind
   the existing bounded queue, delivery ledger and cancellation contract. Keep
   the callback on its native owner; test stop/drain/acknowledgement ordering.
4. **Activity setup:** move model download/import/smoke-test orchestration into a
   lifecycle-aware setup controller. Activity-result launchers stay in the activity.
5. **Build modules:** once dependencies are acyclic, consider JVM libraries for
   pure policy/context and Android libraries for model storage and audio adapters.
   Merely creating Gradle modules does not shrink the APK.

For each step, make a reviewable change, run affected behavioral tests and release
compilation, update this map, and use real phone diagnostics before claiming voice
quality or latency improvement. Do not create a new PR or merge without Justin's
explicit permission.
