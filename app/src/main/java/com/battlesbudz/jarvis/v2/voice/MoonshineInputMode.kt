package com.battlesbudz.jarvis.v2.voice

/** A whole recording includes silence; it is not an already bounded call utterance. */
enum class MoonshineInputMode(val nativeThreshold: String, val diagnosticName: String) {
    CALL_FILTERED("0.0", "bypassed"),
    RAW_DIAGNOSTIC("0.5", "enabled_0.5")
}
