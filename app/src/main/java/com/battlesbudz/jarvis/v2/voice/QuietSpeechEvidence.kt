package com.battlesbudz.jarvis.v2.voice

/** Weak VAD needs corroborating, stable words; low volume alone never confirms speech. */
class QuietSpeechEvidence {
    private var previous = ""
    private var changedAt = 0L
    private var evidenceSince: Long? = null
    fun accept(text: String, probability: Float, nowMs: Long, alreadySpeaking: Boolean): Boolean {
        val normalized = text.trim().lowercase(java.util.Locale.ROOT)
        if (normalized != previous) { previous = normalized; changedAt = nowMs }
        if (normalized.isEmpty() || probability < 0.15f) { evidenceSince = null; return false }
        if (alreadySpeaking && probability >= 0.5f) return true
        if (normalized.split(Regex("\\s+")).size < 2) { evidenceSince = null; return false }
        val since = evidenceSince ?: nowMs.also { evidenceSince = it }
        if (!alreadySpeaking && nowMs - since < 300) return false
        val age = (nowMs - changedAt).coerceAtLeast(0)
        return age in 150..1000 || (alreadySpeaking && age <= 500)
    }
    fun reset() { previous = ""; changedAt = 0; evidenceSince = null }
}
