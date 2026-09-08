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
    private val destination = File(root, "hey-jarvis-kws-v1")
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
                            check(copied <= 25_000_000) { "Unexpected wake-word download size." }
                            output.write(buffer, 0, n)
                        }
                    } }
                } finally { connection.disconnect() }
                check(digest(archive) == "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a") { "Wake-word archive integrity check failed." }
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
        const val SUFFIX = "epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val URL_STRING = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
        private val mutex = Mutex()
        private val files = mapOf(
            "encoder-$SUFFIX" to "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678",
            "decoder-$SUFFIX" to "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a",
            "joiner-$SUFFIX" to "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c",
            "tokens.txt" to "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"
        )
    }
}
