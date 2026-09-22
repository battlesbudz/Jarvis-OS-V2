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
