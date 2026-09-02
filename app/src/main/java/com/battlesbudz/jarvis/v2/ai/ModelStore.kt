package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
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
        hasModel(ModelCatalog.gemma4E2b) && hasModel(ModelCatalog.mobileActions270m)

    fun isUsable(): Boolean =
        isReady() && listOf(ModelCatalog.gemma4E2b, ModelCatalog.mobileActions270m).all { spec ->
            val file = fileFor(spec)
            val key = fingerprintKey(spec)
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
                    .apply()
                clearSmokeTest()
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
