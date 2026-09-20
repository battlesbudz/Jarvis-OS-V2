# Feature and acceptance map

Update this file when adding a feature or learning a reproducible regression. A listed gap is not passing coverage.

| Area | Existing logic checks | Release emulator checks | Remaining device/model checks |
| --- | --- | --- | --- |
| Setup and model browsing | ModelCatalog, ModelGuide and ModelGuidance JVM tests | First-run setup, disabled model check, empty search, browse/cancel without selection | Successful download/import and real model load |
| Model selection | ModelSelection and model-switch JVM tests | Choose another model, Activity recreation, separate-process restart | Switching between loaded engines |
| Tool calling | ActionIntentRouter, NativeActionDecoder, MobileActionValidator and MobileActionPipeline JVM tests | Battery equals Android state; valid volume changes Android; invalid input preserves volume; missing app reports failure | Real model emits correct calls; successful app-launch flow; ambiguous apps/permission combinations |
| Conversations | ConversationHistory, context and policy JVM tests | Not yet exercised through UI: chat is gated by installed/tested weights | Multi-turn generation, durable chat UI and cancellation |
| Voice lifecycle | VoiceSessionController and playback/ASR policy JVM tests; native keyword fixtures | Startup without microphone permission; no claim of microphone capture | Microphone routing, Bluetooth, interruptions, speaker feedback, timing and thermal behavior |
| Packaging | Native ABI, Piper callback, compact APK equivalence checks | Signed normal and compact variants installed/launched | Device GPU/NPU compatibility and resource limits |

The executable device contract is `scripts/verification/scenarios.json`, backed by `app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt`. All seven named methods must finish successfully; skipped methods are failures. `test90` intentionally leaves the selected model for the controller's process-restart check. JVM/native tests run independently in the build job.

For each new feature, write down the entry screen or API, expected state change, a negative case, persisted effects, test level, and evidence path. For new tools, keep validation, Android execution and real-model tool selection as distinct checks. Never turn a canned tool request into a claim that model inference passed.
