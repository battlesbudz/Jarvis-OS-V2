package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import android.content.ContentUris
import android.os.CancellationSignal
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ModelStore(context: Context) {
    private companion object {
        val activeImports = AtomicInteger(0)
        val activeModelOperation = AtomicInteger(0)
        const val PARALLEL_CHUNKS = 6
        const val PARALLEL_DOWNLOAD_THRESHOLD = 128L * 1024L * 1024L
    }

    private val preferences = context.getSharedPreferences("model_setup", Context.MODE_PRIVATE)
    private val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }

    init {
        if (activeImports.get() == 0 && preferences.getBoolean("import_in_progress", false)) {
            preferences.edit().putBoolean("import_in_progress", false).apply()
            modelDirectory.listFiles()
                ?.filter { it.name.endsWith(".part") }
                ?.forEach { it.delete() }
        }
    }

    fun fileFor(spec: LocalModelSpec): File = File(modelDirectory, spec.fileName)

    fun hasModel(spec: LocalModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() > 0L }

    fun isReady(): Boolean =
        hasModel(ModelCatalog.gemma4E2b)

    fun isUsable(): Boolean {
        val spec = ModelCatalog.gemma4E2b
        val file = fileFor(spec)
        val key = fingerprintKey(spec)
        return isReady() &&
            !preferences.getBoolean("${key}_invalid", false) &&
            preferences.contains(key) &&
            preferences.getLong("${key}_length", -1L) == file.length() &&
            preferences.getLong("${key}_modified", -1L) == file.lastModified()
    }

    /**
     * Verifies that the file is unchanged since it was accepted by setup.
     * A first verification pins an imported file's SHA-256 so a later file
     * replacement cannot silently pass the smoke test.
     */
    fun verifyIntegrity(
        spec: LocalModelSpec,
        onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        val file = fileFor(spec)
        if (!file.isFile || file.length() == 0L) return false
        val length = file.length()
        val modified = file.lastModified()
        val key = fingerprintKey(spec)
        val pinned = preferences.getString(key, null)
        if (pinned != null && preferences.getBoolean("${key}_invalid", false)) return false
        if (pinned != null &&
            preferences.getLong("${key}_length", -1L) == length &&
            preferences.getLong("${key}_modified", -1L) == modified
        ) {
            return true
        }
        onProgress(0L, length)
        val actual = file.sha256(onProgress)
        val enforceCatalogHash = preferences.getBoolean("${key}_enforce_catalog_hash", pinned == null)
        if (enforceCatalogHash) spec.expectedSha256?.let { expected ->
            if (!actual.equals(expected, ignoreCase = true)) {
                markIntegrityInvalid(key, length, modified)
                return false
            }
        }
        if (pinned != null && !actual.equals(pinned, ignoreCase = true)) {
            markIntegrityInvalid(key, length, modified)
            return false
        }
        preferences.edit()
            .putString(key, actual)
            .putLong("${key}_length", length)
            .putLong("${key}_modified", modified)
            .putBoolean("${key}_invalid", false)
            .apply()
        return true
    }

    fun smokeTestPassed(): Boolean = preferences.getBoolean("smoke_test_passed", false)

    fun importInProgress(): Boolean = preferences.getBoolean("import_in_progress", false)

    fun tryBeginModelOperation(): Boolean = activeModelOperation.compareAndSet(0, 1)

    fun endModelOperation() {
        activeModelOperation.set(0)
    }

    fun isModelOperationActive(): Boolean = activeModelOperation.get() > 0

    /** Reuses a verified app copy, imports a matching local file, or downloads the pinned model. */
    suspend fun downloadOrReuse(
        spec: LocalModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onStatus: (String) -> Unit = {}
    ): Result<File> = runCatching {
        check(tryBeginModelOperation()) { "Another model operation is still running." }
        try {
            onStatus("Checking Jarvis’s app storage…")
            if (verifyIntegrity(spec, onProgress)) {
                onProgress(fileFor(spec).length(), fileFor(spec).length())
                return@runCatching fileFor(spec)
            }
            // Try the exact Downloads lookup first, but preserve download as
            // the explicit one-tap setup fallback when Android hides the file
            // from the app's background provider query.
            onStatus("Searching Downloads for the exact filename: ${spec.fileName}")
            val firstLookup = withTimeoutOrNull(30_000L) {
                DownloadLookupResult.Completed(findExactDownloadedModel(spec))
            }
            val exactDownload = when (firstLookup) {
                is DownloadLookupResult.Completed -> firstLookup.uri
                null -> {
                    onStatus("The Downloads index is slow. Retrying the exact filename check…")
                    when (val retry = withTimeoutOrNull(30_000L) {
                        DownloadLookupResult.Completed(findExactDownloadedModel(spec))
                    }) {
                        is DownloadLookupResult.Completed -> retry.uri
                        null -> error("Could not finish checking Downloads for ${spec.fileName}. No download was started.")
                    }
                }
            }
            if (exactDownload != null) {
                onStatus("Found ${spec.fileName} in Downloads. Verifying that exact file…")
                onStatus("Importing the existing Gemma model from Downloads…")
                val imported = importExactDownloadedModel(exactDownload, spec, onProgress, onStatus)
                if (imported != null) return@runCatching imported
            }
            onStatus("No exact ${spec.fileName} file was found in Downloads. Starting the verified download…")
            onStatus("Downloading Gemma from the verified model source…")
            val url = requireNotNull(spec.downloadUrl) { "No automatic download is configured for ${spec.id}." }
            val destination = fileFor(spec)
            val temporary = File(modelDirectory, "${spec.fileName}.part")
            downloadModelResumably(
                url = url,
                temporary = temporary,
                onProgress = onProgress,
                onStatus = onStatus
            )
            check(temporary.isFile && temporary.length() > 0L) { "The downloaded model is empty." }
            onStatus("Verifying the downloaded Gemma model…")
            val actualSha256 = temporary.sha256(onProgress)
            spec.expectedSha256?.let { expected ->
                check(actualSha256.equals(expected, ignoreCase = true)) {
                    "The downloaded model failed integrity verification."
                }
            }
            if (destination.exists()) check(destination.delete()) {
                "Unable to replace the previous model file."
            }
            check(temporary.renameTo(destination)) { "Unable to finalize the downloaded model." }
            val key = fingerprintKey(spec)
            preferences.edit()
                .putString(key, actualSha256)
                .putLong("${key}_length", destination.length())
                .putLong("${key}_modified", destination.lastModified())
                .putBoolean("${key}_invalid", false)
                .putBoolean("${key}_enforce_catalog_hash", true)
                .putBoolean("smoke_test_passed", false)
                .apply()
            destination
        } finally {
            endModelOperation()
        }
    }

    /**
     * Downloads large model files using resumable HTTP ranges. Several ranges
     * are fetched concurrently when the host supports Range requests; each
     * range has its own checkpoint file so an interrupted setup resumes without
     * discarding completed work.
     */
    private suspend fun downloadModelResumably(
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

        val chunkDirectory = File(modelDirectory, "${temporary.name}.chunks")
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

    private suspend fun findExactDownloadedModel(spec: LocalModelSpec): Uri? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            val result = runCatching {
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.RELATIVE_PATH
                )
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(spec.fileName),
                    null,
                    cancellationSignal
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        if (cursor.getString(pathIndex).equals(
                                "${Environment.DIRECTORY_DOWNLOADS}/",
                                ignoreCase = true
                            )
                        ) {
                            return@use ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idIndex)
                            )
    }

}
                    null
                }
            }.getOrNull()
                ?: findExactFileInMediaStore(spec, cancellationSignal)
                ?: findExactDownloadsDocument(spec)
            if (continuation.isActive) continuation.resume(result)
        }

    /**
     * Some Android builds expose Downloads files through Files rather than
     * MediaStore.Downloads. Keep this an exact display-name/path query; it is
     * not a storage scan.
     */
    private fun findExactFileInMediaStore(
        spec: LocalModelSpec,
        cancellationSignal: CancellationSignal
    ): Uri? = runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arrayOf(spec.fileName),
            null,
            cancellationSignal
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                if (cursor.getString(pathIndex).equals(
                        "${Environment.DIRECTORY_DOWNLOADS}/",
                        ignoreCase = true
                    )
                ) {
                    return@use ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                }
            }
            null
        }
    }.getOrNull()

    /**
     * The system picker reads the Downloads DocumentsProvider, which can contain
     * files that are not yet represented by the MediaStore Downloads table.
     * Query that same provider as an exact-name fallback so setup agrees with
     * what the user sees when manually importing from Downloads.
     */
    private fun findExactDownloadsDocument(spec: LocalModelSpec): Uri? {
        // DocumentsUI exposes the phone's public Downloads folder through the
        // external-storage provider as primary:Download. This is the provider
        // shown by the picker in the setup screenshots.
        findExactDocumentInChildren(
            authority = "com.android.externalstorage.documents",
            parentDocumentId = "primary:Download",
            spec = spec
        )?.let { return it }

        return runCatching {
            val authority = "com.android.providers.downloads.documents"
            val rootProjection = arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE
            )
            val rootDocumentId = context.contentResolver.query(
                DocumentsContract.buildRootsUri(authority),
                rootProjection,
                null,
                null,
                null
            )?.use { cursor ->
                var selected: String? = null
                val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                val titleIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(documentIdIndex)
                    val title = cursor.getString(titleIndex)
                    if (title.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true) ||
                        documentId.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true)
                    ) {
                        selected = documentId
                        break
                    }
                }
                selected
            } ?: return@runCatching null

            findExactDocumentInChildren(authority, rootDocumentId, spec)
        }.getOrNull()
    }

    private fun findExactDocumentInChildren(
        authority: String,
        parentDocumentId: String,
        spec: LocalModelSpec
    ): Uri? = runCatching {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == spec.fileName) {
                    return@use DocumentsContract.buildDocumentUri(authority, cursor.getString(idIndex))
                }
            }
            null
        }
    }.getOrNull()

    private sealed interface DownloadLookupResult {
        data class Completed(val uri: Uri?) : DownloadLookupResult
    }

    private fun importExactDownloadedModel(
        uri: Uri,
        spec: LocalModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStatus: (String) -> Unit
    ): File? {
        val temporary = File(modelDirectory, "${spec.fileName}.part")
        return runCatching {
            temporary.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val totalBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?.use { it.length }
                        ?.takeIf { it > 0L }
                        ?: -1L
                    var copiedBytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        onProgress(copiedBytes, totalBytes)
                    }
                    output.flush()
                }
            } ?: return@runCatching null
            if (!temporary.isFile || temporary.length() == 0L) return@runCatching null
            onStatus("Verifying the Gemma model copied from Downloads…")
            val actualSha256 = temporary.sha256(onProgress)
            if (spec.expectedSha256 != null &&
                !actualSha256.equals(spec.expectedSha256, ignoreCase = true)
            ) return@runCatching null
            val destination = fileFor(spec)
            if (destination.exists()) check(destination.delete())
            check(temporary.renameTo(destination)) { "Unable to finalize the existing model file." }
            val key = fingerprintKey(spec)
            preferences.edit()
                .putString(key, actualSha256)
                .putLong("${key}_length", destination.length())
                .putLong("${key}_modified", destination.lastModified())
                .putBoolean("${key}_invalid", false)
                .putBoolean("${key}_enforce_catalog_hash", true)
                .putBoolean("smoke_test_passed", false)
                .apply()
            destination
        }.getOrNull().also {
            if (it == null) temporary.delete()
        }
    }

    fun markSmokeTestPassed() {
        preferences.edit().putBoolean("smoke_test_passed", true).apply()
    }

    fun clearSmokeTest() {
        preferences.edit().putBoolean("smoke_test_passed", false).apply()
    }

    suspend fun importModel(uri: Uri, spec: LocalModelSpec): Result<File> {
        val destination = fileFor(spec)
        return runCatching {
            // Create the temporary file inside runCatching so storage errors
            // are returned through the UI callback instead of escaping launch.
            check(tryBeginModelOperation()) { "Another model operation is still running." }
            val temporary = try {
                File.createTempFile("${spec.fileName}.", ".part", modelDirectory)
            } catch (error: Throwable) {
                endModelOperation()
                throw error
            }
            activeImports.incrementAndGet()
            preferences.edit().putBoolean("import_in_progress", true).apply()
            try {
                val selectedName = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                require(selectedName == null || selectedName == spec.fileName) {
                    "Select the ${spec.fileName} model file."
                }
                val resolver = context.contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to open selected model file.")
                require(temporary.length() > 0L) { "The selected model file is empty." }
                val actualSha256 = temporary.sha256()
                // An explicitly selected model is validated by the native
                // Gemma smoke test below, not forced to match the catalog's
                // download hash. This makes “upload your own E2B” supported.
                check(temporary.renameTo(destination)) { "Unable to finalize model file." }
                val fingerprint = fingerprintKey(spec)
                preferences.edit()
                    .putString(fingerprint, actualSha256)
                    .putLong("${fingerprint}_length", destination.length())
                    .putLong("${fingerprint}_modified", destination.lastModified())
                    .putBoolean("${fingerprint}_invalid", false)
                    .putBoolean("${fingerprint}_enforce_catalog_hash", false)
                    .putBoolean("smoke_test_passed", false)
                    .apply()
                destination
            } finally {
                temporary.delete()
                if (activeImports.decrementAndGet() == 0) {
                    preferences.edit().putBoolean("import_in_progress", false).apply()
                }
                endModelOperation()
            }
        }
    }

    private val context: Context = context.applicationContext

    private fun fingerprintKey(spec: LocalModelSpec): String =
        "sha256_${spec.id}"

    private fun markIntegrityInvalid(key: String, length: Long, modified: Long) {
        preferences.edit()
            .putLong("${key}_length", length)
            .putLong("${key}_modified", modified)
            .putBoolean("${key}_invalid", true)
            .apply()
    }

    private fun File.sha256(
        onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val totalBytes = length()
            var processedBytes = 0L
            var lastReportedBytes = -1L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    processedBytes += count
                    if (processedBytes == totalBytes ||
                        lastReportedBytes < 0L ||
                        processedBytes - lastReportedBytes >= 1L * 1024L * 1024L
                    ) {
                        lastReportedBytes = processedBytes
                        onProgress(processedBytes, totalBytes)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
