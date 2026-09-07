package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Downloads and atomically installs the pinned Sherpa Kokoro model bundle. */
class KokoroModelStore(context: Context) {
    companion object {
        private const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
        private const val DIRECTORY = "kokoro-en-v0_19"
        private val REQUIRED = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")
    }

    private val root = File(context.filesDir, "voice-models")
    private val modelDir = File(root, DIRECTORY)

    fun directory(): File = modelDir

    fun isReady(): Boolean = REQUIRED.all { File(modelDir, it).let { file -> file.exists() && (file.isDirectory || file.length() > 0L) } }

    suspend fun downloadOrReuse(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onStatus: (String) -> Unit = {}
    ): Result<File> = runCatching {
        if (isReady()) return@runCatching modelDir
        root.mkdirs()
        val archive = File(root, "$DIRECTORY.tar.bz2.part")
        onStatus("Downloading the local Jarvis voice model…")
        val connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode in 200..299) { "Voice model download failed with HTTP ${connection.responseCode}." }
            val total = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, total)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
        val staging = File(root, "$DIRECTORY.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        onStatus("Installing and checking the local voice model…")
        FileInputStream(archive).use { input ->
            TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val isRootEntry = entry.name == DIRECTORY || entry.name == "$DIRECTORY/"
                    val relative = entry.name.substringAfter("$DIRECTORY/", "")
                    if (!isRootEntry && relative.isNotBlank() && !relative.startsWith("/")) {
                        val output = File(staging, relative)
                        check(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Voice model archive contained an unsafe path."
                        }
                        if (entry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { tar.copyTo(it) }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        check(REQUIRED.all { File(staging, it).exists() }) { "Voice model archive is incomplete." }
        modelDir.deleteRecursively()
        check(staging.renameTo(modelDir)) { "Unable to finalize the voice model." }
        archive.delete()
        modelDir
    }
}
