package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Pinned ASR runtime files, verified before use and installed atomically per file. */
class AsrModelStore(context: Context) {
    private val retiredDirectory = File(context.filesDir, "voice-models/zipformer-en-20m")
    private val directory = File(context.filesDir, "voice-models/moonshine-small-en-26-08-21")
    private val files = MOONSHINE_FILES
    private val base = MOONSHINE_BASE

    suspend fun ensureReady(report: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            retiredDirectory.deleteRecursively()
            directory.mkdirs()
            var completed = 0L
            for (spec in files) {
                coroutineContext.ensureActive()
                val file = File(directory, spec.name)
                if (file.length() != spec.bytes || digest(file) != spec.sha256) {
                    val temporary = File(directory, spec.name + ".part")
                    val connection = URL("$base/${spec.name}").openConnection() as HttpURLConnection
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 30_000
                    try {
                        check(connection.responseCode == 200) { "Speech recognition download failed: HTTP ${connection.responseCode}" }
                        var copied = 0L
                        var lastReport = 0L
                        connection.inputStream.use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    check(copied <= spec.bytes) { "Speech recognition file exceeds its expected size." }
                                    output.write(buffer, 0, count)
                                    if (copied - lastReport >= 1024 * 1024) {
                                        lastReport = copied
                                        report("Preparing ${MoonshineModelInfo.label}: ${(completed + copied) / 1_000_000} / ${files.sumOf { it.bytes } / 1_000_000} MB")
                                    }
                                }
                            }
                        }
                        check(temporary.length() == spec.bytes && digest(temporary) == spec.sha256) {
                            "Speech recognition file failed integrity verification. Please retry."
                        }
                        check(temporary.renameTo(file)) { "Unable to install speech recognition model." }
                    } finally {
                        connection.disconnect()
                        temporary.delete()
                    }
                }
                completed += spec.bytes
            }
            directory
        }
    }

    private fun digest(file: File): String {
        if (!file.isFile) return ""
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MOONSHINE_BASE = "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21"
        private val MOONSHINE_FILES = listOf(
            ModelFile("adapter.ort", 2870368, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762"),
            ModelFile("cross_kv.ort", 5356536, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6"),
            ModelFile("decoder_kv.ort", 81878600, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b"),
            ModelFile("encoder.ort", 44148576, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993"),
            ModelFile("frontend.model.ort", 26944, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f"),
            ModelFile("frontend.weights.ort", 7769464, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338"),
            ModelFile("streaming_config.json", 512, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a"),
            ModelFile("tokenizer.bin", 249974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d")
        )
        private val installMutex = Mutex()
        private data class ModelFile(val name: String, val bytes: Long, val sha256: String)
    }
}
