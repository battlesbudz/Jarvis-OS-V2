package com.battlesbudz.jarvis.v2.memory

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.Keep

@Keep
object MemorySensitivityPolicy {
    const val PRIVATE_COPY = "[Sensitive memory content is available only in a live unlocked view.]"
    fun durableCopy(text: String, sensitiveContext: Boolean): String = if (sensitiveContext) PRIVATE_COPY else text
    /** Scan the whole source before exportable comparison sinks truncate or cache it. */
    fun comparisonCopy(text: String, sensitiveContext: Boolean): String = when {
        sensitiveContext -> PRIVATE_COPY
        SourceTextPersistencePolicy.excluded(text) -> SourceTextPersistencePolicy.EXCLUDED
        classify(text, MemorySensitivity.NORMAL) == MemorySensitivity.RESTRICTED -> PRIVATE_COPY
        else -> text
    }
    /** Typed numbers/booleans remain useful; freeform route/error/PCM summaries are excluded. */
    fun comparisonTtsMetrics(metrics: com.battlesbudz.jarvis.v2.voice.TtsSessionMetrics) = org.json.JSONObject()
        .put("load_ms", metrics.loadMs).put("first_pcm_ms", metrics.firstPcmMs ?: org.json.JSONObject.NULL)
        .put("synthesis_ms", metrics.synthesisMs).put("audio_ms", metrics.audioMs).put("queue_wait_ms", metrics.queueWaitMs)
        .put("playback_speed", metrics.playbackSpeed).put("supply_gap_ms", metrics.supplyGapMs)
        .put("underruns", metrics.underruns).put("phrases", metrics.phrases).put("text_chars", metrics.textChars)
        .put("threads", metrics.threads).put("completed", metrics.completed)
        .put("played_frames", metrics.playedFrames ?: org.json.JSONObject.NULL)
        .put("first_text_to_pcm_ms", metrics.firstTextToPcmMs ?: org.json.JSONObject.NULL)
        .put("first_text_to_playback_ms", metrics.firstTextToPlaybackMs ?: org.json.JSONObject.NULL)
    fun comparisonAsrMetrics(metrics: com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics) = org.json.JSONObject()
        .put("load_ms", metrics.modelLoadMs).put("ready_ms", metrics.captureReadyMs).put("audio_ms", metrics.audioMs)
        .put("decode_ms", metrics.decodeMs).put("max_chunk_ms", metrics.maxDecodeChunkMs)
        .put("first_partial_ms", metrics.firstPartialAfterSpeechMs ?: org.json.JSONObject.NULL)
        .put("partial_updates", metrics.partialUpdates).put("finalization_ms", metrics.finalizationMs)
        .put("empty_candidates", metrics.emptyCandidates).put("endpoint_ms", metrics.endpointDetectionMs ?: org.json.JSONObject.NULL)
    fun deliveryCopy(delivery: com.battlesbudz.jarvis.v2.voice.SpeechDelivery, sensitiveContext: Boolean) =
        if (sensitiveContext) delivery.copy(spans = delivery.spans.map { it.copy(text = PRIVATE_COPY) }) else delivery
    /** Stable release boundary: DTO construction stays inside the target APK. */
    fun publishSensitiveDelivery(controller: com.battlesbudz.jarvis.v2.voice.VoiceSessionController,
        callId: String, replyId: String, rawText: String): String {
        val delivery = deliveryCopy(com.battlesbudz.jarvis.v2.voice.SpeechDelivery(replyId, spans = listOf(
            com.battlesbudz.jarvis.v2.voice.DeliveredSpeechSpan(0, rawText, 0, 10, 10, true))), true)
        controller.updateDelivery(callId, delivery)
        return controller.currentTranscript().single { it.replyId == replyId }.delivery!!.spans.single().text
    }
    private val sensitive = Regex("\\b(?:health|diagnos\\w*|medication|therapy|medical|bank|salary|income|balance|financial|religion|sexual|address|identity|passport)\\b", RegexOption.IGNORE_CASE)
    fun classify(text: String, suggested: MemorySensitivity): MemorySensitivity =
        if (suggested == MemorySensitivity.RESTRICTED || sensitive.containsMatchIn(text) || MemoryPolicy.containsRawRestrictedContent(text))
            MemorySensitivity.RESTRICTED else MemorySensitivity.NORMAL
    fun mayDisclose(record: MemoryRecord, unlocked: Boolean): Boolean =
        record.source.sensitivity == MemorySensitivity.NORMAL || unlocked
    fun unlocked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)
        ?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true
}

/** The visible surfaces discard their composition when lock notification arrives. */
@Composable
fun memoryDisclosureUnlocked(): Boolean {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(MemorySensitivityPolicy.unlocked(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                unlocked = intent?.action != android.content.Intent.ACTION_SCREEN_OFF && MemorySensitivityPolicy.unlocked(context ?: return)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return unlocked && MemorySensitivityPolicy.unlocked(context)
}
