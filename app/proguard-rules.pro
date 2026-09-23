# These SDKs use native string lookups for Kotlin classes, fields and callbacks.
# Preserve their Java/Kotlin ABI while R8 shrinks unused application/AndroidX code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Only JNI entry points and native-created DTOs need stable names. Keeping the
# entire SDK would retain unused download/TTS/UI helpers and their dependencies.
-keep class ai.moonshine.voice.JNI { *; }
-keep class ai.moonshine.voice.Transcript { *; }
-keep class ai.moonshine.voice.TranscriptLine { *; }
-keep class ai.moonshine.voice.TranscriberOption { *; }
-keep class ai.moonshine.voice.WordTiming { *; }
-keep class ai.moonshine.voice.SpeakerSpan { *; }
-keep class ai.moonshine.voice.TtsSynthesisResult { *; }
-keep class ai.moonshine.voice.SpeechClip { *; }
-keep class ai.moonshine.voice.TtsChunk { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

-keep class com.battlesbudz.jarvis.v2.voice.MicroWakeWord { *; }
-keep class com.battlesbudz.jarvis.v2.voice.MicroWakeWord$Companion { *; }

# Concrete callback signature used by Sherpa Piper JNI. Kotlin lambdas are not ABI-stable.
-keep class com.battlesbudz.jarvis.v2.voice.SherpaPcmCallback { *; }

# The separately shrunk release instrumentation APK shares the target's class
# loader. Preserve this shared ABI: otherwise R8 can remove/reshape a Kotlin
# method used only by AndroidJUnitRunner (e.g. Intrinsics.checkNotNullParameter),
# producing NoSuchMethodError before any test starts. The rest of the app and
# AndroidX remain optimized; these rules also apply to the APK we actually ship.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.lifecycle.Lifecycle** { *; }
# AndroidX Test core/runner also calls these shared dependencies directly.
# Audited against the release test DEX's external method and field owners.
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class androidx.annotation.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }

# Explicit application boundary exercised by the release integration tests.
-keep class com.battlesbudz.jarvis.v2.actions.MobileAction** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionRequest { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionValidation** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ExecutionResult { *; }
# Release instrumentation reaches the multi-action contract through the shared test DEX.
-keep class com.battlesbudz.jarvis.v2.actions.ActionTurnPlan** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionTurnRunner** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.NativeActionDecoder { *; }
-keep class com.battlesbudz.jarvis.v2.ai.ToolCall { *; }
# Release instrumentation reads durable multi-action receipts after cancellation/recreation.
-keep class com.battlesbudz.jarvis.v2.chat.ConversationHistory { *; }
-keep class com.battlesbudz.jarvis.v2.chat.ConversationMessage { *; }
-keep class com.battlesbudz.jarvis.v2.chat.ActionReceipt { *; }
# Return values traversed by the release test DEX while checking persisted receipts.
-keep class com.battlesbudz.jarvis.v2.chat.ConversationThread { *; }
-keep class com.battlesbudz.jarvis.v2.ChatEntry { *; }

# Release instrumentation exercises the local finalized-input to approved-packet
# boundary and the production prompt builder without model weights.
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemory { *; }
-keep class com.battlesbudz.jarvis.v2.memory.FinalMemoryInput { *; }
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult { *; }
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemoryOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemorySource { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryOs { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryProposal { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemorySource { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryRecord { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryResult { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryPacketResult { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryContextPacket { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryReviewStatus { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder { *; }
-keep class com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext { *; }

# A controlled Android-test fixture renders the actual conversation surface.
-keep class com.battlesbudz.jarvis.v2.ui.ConversationScreenKt { *; }
-keep class com.battlesbudz.jarvis.v2.ui.MemoryScreenKt { *; }
-keep class com.battlesbudz.jarvis.v2.ai.LocalModelSpec { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceSessionUi { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceSessionState { *; }

# The controlled Compose fixture in release instrumentation directly calls these
# shared top-level composables after replacing the activity content.
-keep class androidx.activity.compose.ComponentActivityKt { *; }
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.ui.Modifier$Companion { *; }
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.ScopeUpdateScope { *; }
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keep class androidx.compose.material3.MaterialThemeKt { *; }
-keep class androidx.compose.material3.SurfaceKt { *; }
-keep class androidx.compose.material3.TextKt { *; }
-keep class androidx.compose.foundation.layout.SizeKt { *; }
-keep class androidx.compose.foundation.layout.BoxKt { *; }
-keep class androidx.compose.ui.semantics.SemanticsModifierKt { *; }
-keep class androidx.compose.ui.semantics.SemanticsProperties_androidKt { *; }

# Natural route release journeys invoke the authoritative routing contract.
-keep class com.battlesbudz.jarvis.v2.ai.TurnOrchestrator { *; }
-keep class com.battlesbudz.jarvis.v2.ai.TurnPlan { *; }
-keep class com.battlesbudz.jarvis.v2.ai.TurnKind { *; }
-keep class com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient { *; }

# Continuous accepted-action release journeys cross this production boundary from
# the separately shrunk instrumentation DEX. Keep only the durable queue/session
# contracts and saved-call DTO/controller API they invoke.
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionTask { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionEvent { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionState { *; }
-keep class com.battlesbudz.jarvis.v2.voice.ContinuousActionSession { *; }
-keep class com.battlesbudz.jarvis.v2.voice.SessionCapture { *; }
-keep class com.battlesbudz.jarvis.v2.voice.CapturedKind** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.CaptureOutcome** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.PendingReport { *; }
-keep class com.battlesbudz.jarvis.v2.voice.ReportDelivery { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceActionControl** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceSessionController { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceCallStore { *; }
-keep class com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceCallRecord { *; }
-keep class com.battlesbudz.jarvis.v2.voice.TranscriptEntry { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceTaskState { *; }
