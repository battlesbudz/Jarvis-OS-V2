package com.battlesbudz.jarvis.v2.ai.storage

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Resumable HTTP transfer only; the store owns identity, hashes and atomic installation. */
internal class ModelDownloader {
    private companion object {
        const val PARALLEL_CHUNKS = 6
        const val PARALLEL_DOWNLOAD_THRESHOLD = 128L * 1024L * 1024L
    }
    /**
     * Downloads large model files using resumable HTTP ranges. Several ranges
     * are fetched concurrently when the host supports Range requests; each
     * range has its own checkpoint file so an interrupted setup resumes without
     * discarding completed work.
     */
    suspend fun download(
        url: String,
        temporary: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStatus: (String) -> Unit
    ) {
        val totalBytes = discoverDownloadSize(url)
        if (totalBytes <= PARALLEL_DOWNLOAD_THRESHOLD) {
            downloadSingleStream(url, temporary, totalBytes, onProgress)
            return
        }

        val chunkDirectory = File(temporary.parentFile, "${temporary.name}.chunks")
        val chunkSize = (totalBytes + PARALLEL_CHUNKS - 1L) / PARALLEL_CHUNKS
        val progressLock = Any()
        val completedBytes = AtomicLong(0L)
        chunkDirectory.mkdirs()
        chunkDirectory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { file ->
            val index = file.name.removeSuffix(".part").toIntOrNull()
            if (index == null || index * chunkSize >= totalBytes) file.delete()
            else completedBytes.addAndGet(file.length().coerceAtMost(chunkSize))
        }
        temporary.delete()
        onStatus("Downloading Gemma in $PARALLEL_CHUNKS resumable parts…")
        onProgress(completedBytes.get(), totalBytes)

        try {
            coroutineScope {
                (0 until PARALLEL_CHUNKS).map { index ->
                    async(Dispatchers.IO) {
                        val start = index * chunkSize
                        if (start >= totalBytes) return@async
                        val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                        val part = File(chunkDirectory, "$index.part")
                        val expected = end - start + 1L
                        if (part.length() > expected) part.delete()
                        if (part.length() < expected) {
                            downloadRange(
                                url = url,
                                start = start + part.length(),
                                end = end,
                                part = part,
                                onBytes = { count ->
                                    val current = completedBytes.addAndGet(count)
                                    synchronized(progressLock) { onProgress(current, totalBytes) }
                                }
                            )
                        }
                        check(part.length() == expected) { "Gemma download part $index is incomplete." }
                    }
                }.awaitAll()
            }
            FileOutputStream(temporary).use { output ->
                for (index in 0 until PARALLEL_CHUNKS) {
                    val start = index * chunkSize
                    if (start >= totalBytes) break
                    val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                    val part = File(chunkDirectory, "$index.part")
                    check(part.length() == end - start + 1L) { "Gemma download part $index is incomplete." }
                    part.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
                }
                output.fd.sync()
            }
            check(temporary.length() == totalBytes) { "Gemma download size is incorrect." }
        } finally {
            chunkDirectory.deleteRecursively()
        }
    }

    private suspend fun discoverDownloadSize(url: String): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL) return -1L
            val range = connection.getHeaderField("Content-Range") ?: return -1L
            range.substringAfterLast("/").toLongOrNull() ?: -1L
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadSingleStream(
        url: String,
        temporary: File,
        totalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val existingBytes = temporary.length()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            check(responseCode in 200..299) { "Model download failed with HTTP $responseCode." }
            val startingBytes = if (append) existingBytes else 0L
            if (!append && existingBytes > 0L) temporary.delete()
            val resolvedTotal = totalBytes.takeIf { it > 0L }
                ?: connection.contentLengthLong.takeIf { it > 0L }?.let { it + startingBytes }
                ?: -1L
            var downloadedBytes = startingBytes
            connection.inputStream.use { input ->
                FileOutputStream(temporary, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, resolvedTotal)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadRange(
        url: String,
        start: Long,
        end: Long,
        part: File,
        onBytes: (Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=$start-$end")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "The Gemma host does not support resumable range downloads."
            }
            part.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(part, start < end && part.exists()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

}
