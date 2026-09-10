package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest

/** Matched inputs, one warm native owner, no tool executor or speech playback. */
internal object GemmaLatencyComparison {
    suspend fun run(context: Context, models: ModelStore, sample: GemmaLatencySample,
                    results: GemmaBenchmarkStore, report: (String) -> Unit,
                    record: (String, String, Int) -> JSONObject) {
        var engine: LiteRtLmEngine? = null
        try {
            report("Loading Gemma for the voice latency comparison…")
            val loadStarted = System.nanoTime()
            val active = LiteRtLmEngine(ModelCatalog.gemma4E2b.id,
                models.fileFor(ModelCatalog.gemma4E2b).path, context.cacheDir.path,
                useGpu = true, visionEnabled = true, audioEnabled = true)
            engine = active
            active.initialize()
            val loadMs = elapsed(loadStarted)
            // Warm both paths. Model loading and these warmups are outside measured requests.
            report("Warming text and audio paths…")
            withTimeout(60_000) { active.generate("Reply with one word: Ready.") {} }
            active.resetConversation()
            withTimeout(60_000) { active.generateAudio("Reply with one word: Ready.", sample.audio) {} }
            val audioHash = sha256(sample.audio)
            for (pass in 1..3) for (case in GemmaLatencyCases.cases(sample, pass)) {
                currentCoroutineContext().ensureActive()
                report("${case.mode}: pass $pass of 3…")
                val row = record(case.mode.name, "voice_latency_v1", pass)
                    .put("source_turn", sample.sourceTurn).put("sample_captured_at_ms", sample.capturedAtMs)
                    .put("model_load_ms", loadMs).put("warmup", "text_and_audio")
                    .put("conversation_state", "fresh_per_case").put("acceleration", "runtime_default")
                    .put("concurrent_asr_tts", false).put("tools_enabled", false)
                    .put("prompt_chars", case.prompt.length).put("prompt_sha256", sha256(case.prompt.toByteArray()))
                    .put("audio_bytes", case.audio?.size ?: 0).put("paired_audio_sha256", audioHash)
                    .put("audio_duration_ms", case.audio?.let { (it.size - 44) * 1000L / 32_000 } ?: 0)
                    .put("audio_format", "captured_16khz_mono_pcm16_wav")
                    .put("native_audio_encode_ms", JSONObject.NULL).put("request_queue_ms", JSONObject.NULL)
                    .put("queue_note", "isolated sequential runner; live queueing not measured")
                    .put("target_ms", 1000).put("spoken_audio", false)
                try {
                    val resetStarted = System.nanoTime()
                    active.resetConversation()
                    row.put("conversation_reset_ms", elapsed(resetStarted))
                    val started = System.nanoTime()
                    var checkedSentenceMs: Long? = null
                    var firstAnswerTextMs: Long? = null
                    val guard = VoiceRepetitionGuard(sample.transcript, null) {
                        if (checkedSentenceMs == null) checkedSentenceMs = elapsed(started)
                    }
                    val filter = AssistantStreamFilter { token ->
                        if (firstAnswerTextMs == null && token.isNotBlank()) firstAnswerTextMs = elapsed(started)
                        guard.accept(token)
                    }
                    val answer = withTimeout(30_000) {
                        if (case.audio == null) active.generate(case.prompt, filter::accept)
                        else active.generateAudio(case.prompt, case.audio, filter::accept)
                    }
                    guard.finish()
                    row.put("first_token_ms", answer.timeToFirstTokenMs)
                        .put("first_token_scope", "sdk_raw_text; may include control text")
                        .put("first_answer_text_ms", firstAnswerTextMs ?: JSONObject.NULL)
                        .put("first_checked_text_ms", checkedSentenceMs ?: JSONObject.NULL)
                        .put("total_ms", answer.totalGenerationTimeMs)
                        .put("native_submit_ms", answer.nativeSubmitMs ?: JSONObject.NULL)
                        .put("first_callback_ms", answer.firstCallbackMs ?: JSONObject.NULL)
                        .put("unexpected_tool_calls", answer.toolCalls.size)
                        .put("suppressed_sentences", guard.suppressedSentences)
                        .put("target_met", if (firstAnswerTextMs == null || answer.toolCalls.isNotEmpty() ||
                            guard.text.isBlank() || guard.suppressedSentences > 0)
                            JSONObject.NULL else firstAnswerTextMs!! < 1000)
                        .put("output", answer.text.take(2000)).put("error", JSONObject.NULL)
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    row.put("error", "Generation exceeded 30 seconds; native cleanup may add time.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { row.put("error", error.message ?: error.javaClass.simpleName) }
                row.put("thermal_status_end", context.getSystemService(PowerManager::class.java).currentThermalStatus)
                    .put("app_pss_mb", Debug.getPss() / 1024)
                results.add(row)
                // Do not reuse a native instance after an inference/cleanup failure.
                if (!row.isNull("error")) return
            }
        } finally { withContext(NonCancellable) { engine?.close() } }
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
