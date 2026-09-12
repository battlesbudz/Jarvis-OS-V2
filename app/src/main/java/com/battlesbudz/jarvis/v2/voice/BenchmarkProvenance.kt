package com.battlesbudz.jarvis.v2.voice

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Hash once per requested suite before timed runs, with bounded IO and cooperative cancellation. */
internal object BenchmarkProvenance {
    suspend fun collect(engine: TtsEngine, directory: File): Map<String, String> {
        if (engine != TtsEngine.POCKET_PAUL) return mapOf("artifactHashStatus" to "not_collected")
        val hashes = linkedMapOf("policy" to PocketSpeechPolicy.VERSION,
            "nativePath" to "jarvis_patched_sherpa_callback", "upstreamParity" to "not_assessed",
            "conditioning" to "paul_reference_lm_prompt_copied_per_submission",
            "nativeStateObservation" to "source_contract_only",
            "stateComparison" to "decoder_rng_and_chunk_startup_together_not_decoder_only", "numSteps" to "5",
            "expectedArchiveSha256" to engine.archiveSha256,
            "hashTiming" to "once_before_suite_not_in_load_or_inference_metrics",
            "modelLifecycle" to "fresh_native_instance_per_case",
            "fileCacheCondition" to "model_files_read_for_hashing_before_suite_not_cold_io")
        hashes.putAll(PocketSpeechPolicy.extra().mapKeys { "generation.${it.key}" })
        for (name in PocketVoiceSpec.files.keys + PocketVoiceSpec.PAUL_FILE) {
            val hash = MessageDigest.getInstance("SHA-256")
            File(directory, name).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            hashes["sha256.$name"] = hash.digest().joinToString("") { "%02x".format(it) }
        }
        return hashes
    }
}
