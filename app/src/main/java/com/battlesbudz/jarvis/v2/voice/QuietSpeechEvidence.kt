package com.battlesbudz.jarvis.v2.voice

/** Weak VAD needs corroborating, stable words; low volume alone never confirms speech. */
class QuietSpeechEvidence {
    private var previous = ""
    private var changedAt = 0L
    fun accept(text: String, probability: Float, nowMs: Long, alreadySpeaking: Boolean): Boolean {
        val normalized = text.trim().lowercase(java.util.Locale.ROOT)
        if (normalized != previous) { previous = normalized; changedAt = nowMs }
        if (normalized.isEmpty()) return false
        if (probability >= 0.5f) return true
        if (probability < 0.15f || normalized.split(Regex("\\s+")).size < 2) return false
        val age = (nowMs - changedAt).coerceAtLeast(0)
        return age in 150..1000 || (alreadySpeaking && age <= 500)
    }
    fun reset() { previous = ""; changedAt = 0 }
}
