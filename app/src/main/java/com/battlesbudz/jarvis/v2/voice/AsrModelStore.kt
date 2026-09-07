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

/** Only the four int8 runtime files are downloaded, verified, and installed atomically. */
class AsrModelStore(context: Context) {
    private val directory = File(context.filesDir, "voice-models/zipformer-en-20m")
    fun isReady(): Boolean = FILES.all { File(directory, it.name).length() == it.bytes }

    suspend fun ensureReady(report: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            directory.mkdirs()
            var completed = 0L
            for (spec in FILES) {
                coroutineContext.ensureActive()
                val file = File(directory, spec.name)
                if (file.length() != spec.bytes || digest(file) != spec.sha256) {
                    val temporary = File(directory, spec.name + ".part")
                    val connection = URL("$BASE/${spec.name}").openConnection() as HttpURLConnection
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
                                        report("Preparing speech recognition: ${(completed + copied) / 1_000_000} / 44 MB")
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
        const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"
        private val installMutex = Mutex()
        private data class ModelFile(val name: String, val bytes: Long, val sha256: String)
        private val FILES = listOf(
            ModelFile(ENCODER, 42845182, "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"),
            ModelFile(DECODER, 539499, "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c"),
            ModelFile(JOINER, 259572, "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"),
            ModelFile("tokens.txt", 5048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb")
        )
    }
}
