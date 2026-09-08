package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.voice.SpeechChunker
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** GPU off/on/off comparison. Uses simulated tool results; no phone action executor exists here. */
class GemmaBenchmarkController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val models: ModelStore,
    private val results: GemmaBenchmarkStore,
    private val canStart: () -> Boolean,
    private val releaseIdleEngine: () -> Unit,
    private val log: (String) -> Unit
) {
    private var job: Job? = null
    val running: Boolean get() = job?.isCompleted == false

    fun start(status: (String) -> Unit, finished: () -> Unit) {
        if (running || !canStart()) {
            status("End the call and wait for other work to finish before benchmarking.")
            finished(); return
        }
        job = scope.launch(Dispatchers.Default) {
            var owned = false
            val runId = UUID.randomUUID().toString()
            var message = "Gemma comparison complete. Live calls keep the model's default acceleration."
            fun report(value: String) { scope.launch(Dispatchers.Main) { status(value) } }
            fun record(mode: String, sample: String, repeat: Int): JSONObject = JSONObject()
                .put("run_id", runId).put("at_ms", System.currentTimeMillis())
                .put("device", Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT)
                .put("runtime", "LiteRT-LM 0.12.0").put("backend", "GPU")
                .put("model_sha256", ModelCatalog.gemma4E2b.expectedSha256)
                .put("mode", mode).put("sample", sample).put("repeat", repeat)
                .put("thermal_status", context.getSystemService(PowerManager::class.java).currentThermalStatus)
            try {
                check(models.tryBeginModelOperation()) { "Another model operation is still running." }
                owned = true
                check(models.verifyIntegrity(ModelCatalog.gemma4E2b)) { "Import the verified Gemma model first." }
                // Avoid keeping a second multi-gigabyte Gemma instance resident during the test.
                releaseIdleEngine()
                for ((block, enabled) in listOf(false, true, false).withIndex()) {
                    ensureActive()
                    val mode = if (enabled) "MTP_ON" else if (block == 0) "MTP_OFF" else "MTP_OFF_RECHECK"
                    var engine: LiteRtLmEngine? = null
                    try {
                        report("$mode: loading Gemma…")
                        val initAt = System.nanoTime()
                        val active = LiteRtLmEngine(ModelCatalog.gemma4E2b.id,
                            models.fileFor(ModelCatalog.gemma4E2b).path, context.cacheDir.path,
                            useGpu = true, tools = MobileActionToolDefinitions.all(), audioEnabled = true,
                            speculativeDecoding = enabled)
                        engine = active
                        active.initialize()
                        val loadMs = (System.nanoTime() - initAt) / 1_000_000
                        withTimeout(60_000) { active.generate("Reply with exactly one word: Ready.") {} }
                        for (repeat in 1..2) for ((sample, prompt) in samples) {
                            ensureActive()
                            report("$mode: $sample, pass $repeat of 2…")
                            active.resetConversation()
                            val startedAt = System.nanoTime()
                            var openingMs: Long? = null
                            val chunker = SpeechChunker()
                            val filter = AssistantStreamFilter { token ->
                                if (openingMs == null) {
                                    chunker.append(token)
                                    if (chunker.take() != null) openingMs = (System.nanoTime() - startedAt) / 1_000_000
                                }
                            }
                            val answer = withTimeout(60_000) { active.generate(prompt, filter::accept) }
                            if (openingMs == null && chunker.take(final = true) != null)
                                openingMs = (System.nanoTime() - startedAt) / 1_000_000
                            val entry = record(mode, sample, repeat).put("load_ms", loadMs)
                                .put("first_token_ms", answer.timeToFirstTokenMs)
                                .put("first_opening_text_ms", openingMs ?: JSONObject.NULL)
                                .put("total_ms", answer.totalGenerationTimeMs)
                                .put("estimated_output_tokens", answer.outputTokens ?: JSONObject.NULL)
                                .put("estimated_decode_tokens_per_second", answer.decodeTokensPerSecond ?: JSONObject.NULL)
                                .put("app_pss_mb", Debug.getPss() / 1024)
                                .put("output", answer.text.take(2000)).put("error", JSONObject.NULL)
                            if (sample == "battery_tool_simulation") {
                                val call = answer.toolCalls.singleOrNull()
                                val correct = call?.name == "read_battery"
                                entry.put("tool_call_correct", correct)
                                if (correct) {
                                    val reply = withTimeout(60_000) {
                                        active.sendToolResult(requireNotNull(call), "Battery is at 42 percent.") {}
                                    }
                                    entry.put("simulated_tool_reply", reply.text.take(1000))
                                        .put("tool_reply_first_token_ms", reply.timeToFirstTokenMs)
                                }
                            } else entry.put("unexpected_tool_calls", answer.toolCalls.size)
                            results.add(entry)
                            log("Gemma benchmark: mode=$mode sample=$sample firstTokenMs=${answer.timeToFirstTokenMs} openingMs=$openingMs")
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        results.add(record(mode, "generation_timeout", 0)
                            .put("error", "Generation exceeded 60 seconds."))
                        log("Gemma benchmark: mode=$mode timed out")
                        report("$mode timed out. Continuing the comparison.")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        results.add(record(mode, "initialization_or_generation_error", 0)
                            .put("error", error.message ?: error.javaClass.simpleName))
                        log("Gemma benchmark: mode=$mode failed=${error.message}")
                        report("$mode could not complete: ${error.message}. Continuing the comparison.")
                    } finally { withContext(NonCancellable) { engine?.close() } }
                }
            } catch (cancelled: CancellationException) {
                message = "Gemma benchmark stopped. Completed measurements remain saved."; throw cancelled
            } catch (error: Exception) { message = "Gemma benchmark failed: ${error.message}" }
            finally {
                if (owned) models.endModelOperation()
                withContext(NonCancellable + Dispatchers.Main) { status(message); finished() }
            }
        }
    }

    fun stop() { job?.cancel() }

    private companion object {
        val samples = linkedMapOf(
            "short_reply" to "Answer in one short sentence: why does a metal spoon feel colder than a wooden spoon in the same room?",
            "spoken_explanation" to "In about sixty words, explain why the sky looks blue. Start with the answer. Use plain spoken English, without headings or formatting.",
            "battery_tool_simulation" to "What is my current battery percentage? Use the read_battery tool."
        )
    }
}
