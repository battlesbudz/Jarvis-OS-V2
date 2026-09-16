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

# Concrete callback signature used by Sherpa Pocket JNI. Kotlin lambdas are not ABI-stable.
-keep class com.battlesbudz.jarvis.v2.voice.SherpaPcmCallback { *; }
