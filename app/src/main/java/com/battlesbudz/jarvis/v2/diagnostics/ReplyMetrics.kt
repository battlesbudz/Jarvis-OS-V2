package com.battlesbudz.jarvis.v2.diagnostics

import androidx.annotation.Keep
import org.json.JSONObject
import java.util.Locale

/** Durable, per-assistant-reply timings. Values are null until actually observed. */
@Keep
data class ReplyMetrics(
    val modelSubmittedAtMs: Long? = null,
    val firstRawTokenAtMs: Long? = null,
    val speechEndedAtMs: Long? = null,
    val firstReplyPlaybackAtMs: Long? = null,
    val estimatedTokensPerSecond: Double? = null
) {
    fun submitted(atMs: Long) = copy(modelSubmittedAtMs = modelSubmittedAtMs ?: atMs)
    fun firstRawToken(atMs: Long) = copy(firstRawTokenAtMs = firstRawTokenAtMs ?: atMs)
    fun speechEnded(atMs: Long) = copy(speechEndedAtMs = speechEndedAtMs ?: atMs)
    fun firstActualPlayback(atMs: Long) = copy(firstReplyPlaybackAtMs = firstReplyPlaybackAtMs ?: atMs)
    fun summary(): String = "TTFT ${duration(modelSubmittedAtMs, firstRawTokenAtMs)} · " +
        "TTF-SW ${duration(speechEndedAtMs, firstReplyPlaybackAtMs)} · " +
        (estimatedTokensPerSecond?.takeIf { it.isFinite() }?.let { "~${String.format(Locale.US, "%.1f", it)} tok/s" } ?: "tok/s —")
    fun json(): JSONObject = JSONObject().put("modelSubmittedAtMs", modelSubmittedAtMs)
        .put("firstRawTokenAtMs", firstRawTokenAtMs).put("speechEndedAtMs", speechEndedAtMs)
        .put("firstReplyPlaybackAtMs", firstReplyPlaybackAtMs).put("estimatedTokensPerSecond", estimatedTokensPerSecond)
    companion object {
        fun read(json: JSONObject?): ReplyMetrics? = json?.let {
            ReplyMetrics(it.longOrNull("modelSubmittedAtMs"), it.longOrNull("firstRawTokenAtMs"),
                it.longOrNull("speechEndedAtMs"), it.longOrNull("firstReplyPlaybackAtMs"),
                it.takeIf { j -> j.has("estimatedTokensPerSecond") }?.optDouble("estimatedTokensPerSecond")?.takeIf(Double::isFinite))
        }
        val unavailable = ReplyMetrics()
        private fun JSONObject.longOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key).takeIf { it >= 0 } else null
    }
    private fun duration(from: Long?, to: Long?): String = if (from == null || to == null || to < from) "—"
        else String.format(Locale.US, "%.2fs", (to - from) / 1000.0)
}
