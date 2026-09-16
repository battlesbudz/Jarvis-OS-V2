package com.battlesbudz.jarvis.v2.voice

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Hash once per requested suite before timed runs, with bounded IO and cooperative cancellation. */
internal object BenchmarkProvenance {
    suspend fun collect(engine: TtsEngine, directory: File): Map<String, String> {
        val hashes = linkedMapOf("nativePath" to "upstream_sherpa_vits", "policy" to "piper-whole-passages-v3",
            "expectedArchiveSha256" to engine.archiveSha256,
            "hashTiming" to "once_before_suite_not_in_load_or_inference_metrics")
        for (name in NorthernPiperSpec.files.keys) {
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
