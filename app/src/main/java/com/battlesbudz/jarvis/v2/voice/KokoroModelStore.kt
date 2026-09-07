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
        // Total uncompressed bytes in the pinned archive above. This lets the
        // UI show unpacked/total without performing a second decompression pass.
        const val EXTRACTED_BYTES = 369_315_617L
        private val REQUIRED = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")
    }

    private val root = File(context.filesDir, "voice-models")
    private val modelDir = File(root, DIRECTORY)

    init {
        // Failed/interrupted installs must never accumulate large leftovers.
        // A later WorkManager attempt starts cleanly if the final model is not ready.
        if (!isReady()) {
            File(root, "$DIRECTORY.tar.bz2.part").delete()
            File(root, "$DIRECTORY.staging").deleteRecursively()
        }
    }

    fun directory(): File = modelDir

    fun isReady(): Boolean = REQUIRED.all { File(modelDir, it).let { file -> file.exists() && (file.isDirectory || file.length() > 0L) } }

    suspend fun downloadOrReuse(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onStatus: (String) -> Unit = {}
    ): Result<File> = runCatching {
        if (isReady()) return@runCatching modelDir
        root.mkdirs()
        val archive = File(root, "$DIRECTORY.tar.bz2.part")
        val staging = File(root, "$DIRECTORY.staging")
        try {
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
            staging.deleteRecursively()
            staging.mkdirs()
            onStatus("Installing Kokoro voice model… unpacking files")
            var extractedBytes = 0L
            var nextProgressReport = 8L * 1024L * 1024L
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
                                FileOutputStream(output).use { outputStream ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                                    var count: Int
                                    while (tar.read(buffer).also { count = it } >= 0) {
                                        if (count == 0) continue
                                        outputStream.write(buffer, 0, count)
                                        extractedBytes += count
                                        if (extractedBytes >= nextProgressReport) {
                                            onStatus(
                                                "Installing Kokoro voice model… " +
                                                    "${extractedBytes / (1024L * 1024L)} MB unpacked"
                                            )
                                            nextProgressReport += 8L * 1024L * 1024L
                                            onProgress(extractedBytes, EXTRACTED_BYTES)
                                        }
                                    }
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
            check(REQUIRED.all { File(staging, it).exists() }) { "Voice model archive is incomplete." }
            modelDir.deleteRecursively()
            check(staging.renameTo(modelDir)) { "Unable to finalize the voice model." }
            modelDir
        } finally {
            // Keep only the finalized model. Failed archives and extracted staging
            // files are disposable and are removed before the next retry.
            archive.delete()
            if (!isReady()) staging.deleteRecursively()
        }
    }
}
