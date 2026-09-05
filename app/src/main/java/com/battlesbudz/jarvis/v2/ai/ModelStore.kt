package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

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
    fun verifyIntegrity(spec: LocalModelSpec): Boolean {
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
        val actual = file.sha256()
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
     * Reuses the canonical app-private copy when it is already present and
     * valid. Otherwise downloads the pinned model into a resumable .part file,
     * verifies it, and atomically installs it under the expected filename.
     */
    suspend fun downloadOrReuse(
        spec: LocalModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = runCatching {
        check(tryBeginModelOperation()) { "Another model operation is still running." }
        try {
            if (verifyIntegrity(spec)) {
                onProgress(fileFor(spec).length(), fileFor(spec).length())
                return@runCatching fileFor(spec)
            }
            // Android does not permit a broad recursive storage scan, but it
            // does allow an exact MediaStore query. Reuse a model the user
            // already downloaded to the public Downloads location before
            // touching the network.
            findExactDownloadedModel(spec)?.let { uri ->
                val imported = importExactDownloadedModel(uri, spec, onProgress)
                if (imported != null) return@runCatching imported
            }
            val url = requireNotNull(spec.downloadUrl) { "No automatic download is configured for ${spec.id}." }
            val destination = fileFor(spec)
            val temporary = File(modelDirectory, "${spec.fileName}.part")
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
                check(responseCode in 200..299) {
                    "Model download failed with HTTP $responseCode."
                }
                val startingBytes = if (append) existingBytes else 0L
                if (!append && existingBytes > 0L) temporary.delete()
                val totalBytes = connection.contentLengthLong
                    .takeIf { it > 0L }
                    ?.let { it + startingBytes }
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
                            onProgress(downloadedBytes, totalBytes)
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
            check(temporary.isFile && temporary.length() > 0L) { "The downloaded model is empty." }
            val actualSha256 = temporary.sha256()
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
                .putBoolean("smoke_test_passed", false)
                .apply()
            destination
        } finally {
            endModelOperation()
        }
    }

    private fun findExactDownloadedModel(spec: LocalModelSpec): Uri? = runCatching {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(spec.fileName),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }
    }.getOrNull()

    private fun importExactDownloadedModel(
        uri: Uri,
        spec: LocalModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
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
            val actualSha256 = temporary.sha256()
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

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
