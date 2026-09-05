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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ModelStore(context: Context) {
    private companion object {
        val activeImports = AtomicInteger(0)
        val activeModelOperation = AtomicInteger(0)
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
        spec.expectedSha256?.let { expected ->
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

    /**
     * Reuses the canonical app-private copy or imports the exact model file
     * already present in Downloads. Network downloading is intentionally
     * disabled until exact local-file registration is proven reliable.
     */
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
            // Android does not permit a broad recursive storage scan, but it
            // does allow an exact MediaStore query. Reuse a model the user
            // already downloaded to the public Downloads location before
            // touching the network.
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
            error("The exact ${spec.fileName} model file was not found in Downloads. No model download is available yet. Use Import an existing model file.")
        } finally {
            endModelOperation()
        }
    }

    private suspend fun findExactDownloadedModel(spec: LocalModelSpec): Uri? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            val result = runCatching {
                val projection = arrayOf(MediaStore.Downloads._ID)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(spec.fileName, "${Environment.DIRECTORY_DOWNLOADS}/"),
                    null,
                    cancellationSignal
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }.getOrNull() ?: findExactDownloadsDocument(spec)
            if (continuation.isActive) continuation.resume(result)
        }

    /**
     * The system picker reads the Downloads DocumentsProvider, which can contain
     * files that are not yet represented by the MediaStore Downloads table.
     * Query that same provider as an exact-name fallback so setup agrees with
     * what the user sees when manually importing from Downloads.
     */
    private fun findExactDownloadsDocument(spec: LocalModelSpec): Uri? = runCatching {
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

        val childProjection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUri(authority, rootDocumentId),
            childProjection,
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
                spec.expectedSha256?.let { expected ->
                    require(actualSha256.equals(expected, ignoreCase = true)) {
                        "The selected model failed integrity verification."
                    }
                }
                check(temporary.renameTo(destination)) { "Unable to finalize model file." }
                val fingerprint = fingerprintKey(spec)
                preferences.edit()
                    .putString(fingerprint, actualSha256)
                    .putLong("${fingerprint}_length", destination.length())
                    .putLong("${fingerprint}_modified", destination.lastModified())
                    .putBoolean("${fingerprint}_invalid", false)
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
