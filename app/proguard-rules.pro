# These SDKs use native string lookups for Kotlin classes, fields and callbacks.
# Preserve their Java/Kotlin ABI while R8 shrinks unused application/AndroidX code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.moonshine.voice.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
