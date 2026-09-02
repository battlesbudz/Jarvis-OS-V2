package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

class ModelStore(context: Context) {
    private val preferences = context.getSharedPreferences("model_setup", Context.MODE_PRIVATE)
    private val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(spec: LocalModelSpec): File = File(modelDirectory, spec.fileName)

    fun hasModel(spec: LocalModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() > 0L }

    fun isReady(): Boolean =
        hasModel(ModelCatalog.gemma4E2b) && hasModel(ModelCatalog.mobileActions270m)

    fun smokeTestPassed(): Boolean = preferences.getBoolean("smoke_test_passed", false)

    fun markSmokeTestPassed() {
        preferences.edit().putBoolean("smoke_test_passed", true).apply()
    }

    fun clearSmokeTest() {
        preferences.edit().putBoolean("smoke_test_passed", false).apply()
    }

    suspend fun importModel(uri: Uri, spec: LocalModelSpec): Result<File> {
        val destination = fileFor(spec)
        // A unique temporary file prevents a second picker result from truncating
        // or renaming the first import while it is still copying.
        val temporary = File.createTempFile("${spec.fileName}.", ".part", modelDirectory)
        return runCatching {
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
            spec.expectedSha256?.let { expected ->
                require(temporary.sha256().equals(expected, ignoreCase = true)) {
                    "The selected model failed integrity verification."
                }
            }
            check(temporary.renameTo(destination)) { "Unable to finalize model file." }
            clearSmokeTest()
            destination
        }.onFailure {
            temporary.delete()
        }
    }

    private val context: Context = context.applicationContext

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