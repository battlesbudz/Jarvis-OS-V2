package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A small dedicated keyword model; Moonshine remains the only call transcriber. */
class WakeWordModelStore(context: Context) {
    private val root = File(context.filesDir, "voice-models")
    private val destination = File(root, "hey-jarvis-kws-v2")
    suspend fun ensureReady(report: (String) -> Unit): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (valid(destination)) return@withLock destination
            root.mkdirs()
            val archive = File(root, "hey-jarvis-kws.part")
            val staging = File(root, "hey-jarvis-kws.staging")
            try {
                report("Preparing Hey Jarvis wake-word model…")
                val connection = URL(URL_STRING).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                try {
                    check(connection.responseCode == 200) { "Wake-word download failed: HTTP ${connection.responseCode}" }
                    connection.inputStream.use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            check(copied <= 40_000_000) { "Unexpected wake-word download size." }
                            output.write(buffer, 0, n)
                        }
                    } }
                } finally { connection.disconnect() }
                check(digest(archive) == "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6") { "Wake-word archive integrity check failed." }
                staging.deleteRecursively()
                staging.mkdirs()
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream())).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        coroutineContext.ensureActive()
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isFile && name in files) {
                            check(entry.size <= 6_000_000) { "Unexpected keyword model file size." }
                            File(staging, name).outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextTarEntry
                    }
                }
                check(valid(staging)) { "Wake-word model integrity check failed." }
                destination.deleteRecursively()
                check(staging.renameTo(destination)) { "Unable to install wake-word model." }
                File(root, "hey-jarvis-kws-v1").deleteRecursively()
                destination
            } finally { archive.delete(); staging.deleteRecursively() }
        }
    }
    private fun valid(dir: File) = files.all { (name, hash) -> digest(File(dir, name)) == hash }
    private fun digest(file: File): String {
        if (!file.isFile) return ""
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val DECODER_FILE = "decoder-epoch-13-avg-2-chunk-16-left-64.onnx"
        const val SUFFIX = "epoch-13-avg-2-chunk-16-left-64.int8.onnx"
        private const val URL_STRING = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2"
        private val mutex = Mutex()
        private val files = mapOf(
            "encoder-$SUFFIX" to "408bbd740838c42d5bf6d1c5b80b3c88b616c7860b92d980328b5b068c76ae48",
            DECODER_FILE to "63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21",
            "joiner-$SUFFIX" to "190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1",
            "tokens.txt" to "2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0"
        )
    }
}
