package com.battlesbudz.jarvis.v2.diagnostics

import android.os.BatteryManager
import com.battlesbudz.jarvis.v2.JarvisRuntime
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.TtsEngine

internal fun JarvisRuntime.newPipelineBenchmark(turnId: String, channel: String,
    asr: AsrEngine? = null, tts: TtsEngine? = null): PipelineBenchmarkCapture {
    val selected = modelStore.selectedModel()
    val models = linkedMapOf("llm" to PipelineBenchmarkModel(selected.id, "LiteRT-LM", "0.16.0",
        if (selected.recommendedGpu) "GPU_requested" else "CPU_requested", selected.expectedSha256))
    asr?.let { models["asr"] = PipelineBenchmarkModel(it.id,
        if (it == AsrEngine.MOONSHINE) "Moonshine" else "Sherpa-ONNX", it.modelVersion, "CPU") }
    tts?.let { models["tts"] = PipelineBenchmarkModel(it.id, "Sherpa-ONNX", it.version, "CPU") }
    val capture = PipelineBenchmarkCapture(turnId, channel, System.currentTimeMillis(),
        pipelineBenchmarkStore.captureProvenance(models, mapOf(
            "llm_asset_bytes" to modelStore.fileFor(selected).length().toString(),
            "asset_hash_provenance" to "catalog_expected_not_rehashed_for_benchmark",
            "context_token_limit" to (selected.contextTokens?.toString() ?: "runtime_default"),
            "token_count_method" to "visible_char4_estimate_native_ids_unavailable",
            "microphone_source_requested" to if (channel == "voice") "pending_recorder" else "not_applicable",
            "speaker_identity" to "disabled", "physical_audio_audibility" to "not_measured",
            "tts_threads" to if (tts != null) "4" else "not_applicable")))
    pipelineBenchmarkStore.resourceMetrics().forEach { (key, value) -> capture.metric("${key}_before", value) }

    return capture
}

internal fun JarvisRuntime.finishPipelineResources(capture: PipelineBenchmarkCapture) {
    pipelineBenchmarkStore.resourceMetrics().forEach { (key, value) -> capture.metric("${key}_after", value) }
    capture.metric("battery_percent_after", runCatching { getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } }.getOrNull())
}
