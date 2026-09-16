package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ai.storage.ModelDownloader
import com.battlesbudz.jarvis.v2.ai.storage.DownloadedModelLookup
import com.battlesbudz.jarvis.v2.ai.storage.sha256
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull

class ModelStore(context: Context) {
    private val downloader = ModelDownloader()
    private val downloadedModels = DownloadedModelLookup(context)

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

    fun selectedModel(): LocalModelSpec = ModelCatalog.resolve(preferences.getString("selected_model", null))

    /** Caller owns the model-operation lock and has released the idle native engine. */
    fun selectModel(spec: LocalModelSpec) {
        require(ModelCatalog.find(spec.id) == spec) { "Unsupported model." }
        check(isModelOperationActive()) { "Model selection requires exclusive ownership." }
        check(preferences.edit().putString("selected_model", spec.id).commit()) {
            "Could not save the selected model."
        }
    }

    private fun smokeTestKey(spec: LocalModelSpec) = "smoke_test_passed_${spec.id}"

    fun fileFor(spec: LocalModelSpec): File = File(modelDirectory, spec.fileName)

    fun hasModel(spec: LocalModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() > 0L }

    fun isReady(): Boolean =
        hasModel(selectedModel())

    fun isUsable(): Boolean {
        val spec = selectedModel()
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

    fun smokeTestPassed(): Boolean {
        val spec = selectedModel()
        return preferences.getBoolean(smokeTestKey(spec),
            spec == ModelCatalog.gemma4E2b && preferences.getBoolean("smoke_test_passed", false))
    }

    fun smokeTestAttempted(): Boolean =
        preferences.getBoolean("smoke_test_attempted_${selectedModel().id}", false)

    // Persist before native initialization: a process death must leave setup recoverable.
    fun markSmokeTestStarted() {
        val spec = selectedModel()
        check(preferences.edit()
            .putBoolean("smoke_test_attempted_${spec.id}", true)
            .putBoolean(smokeTestKey(spec), false).commit()) { "Could not save model test state." }
    }

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
                DownloadLookupResult.Completed(downloadedModels.find(spec))
            }
            val exactDownload = when (firstLookup) {
                is DownloadLookupResult.Completed -> firstLookup.uri
                null -> {
                    onStatus("The Downloads index is slow. Retrying the exact filename check…")
                    when (val retry = withTimeoutOrNull(30_000L) {
                        DownloadLookupResult.Completed(downloadedModels.find(spec))
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
            downloader.download(
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
                .putBoolean(smokeTestKey(spec), false)
                .putBoolean("smoke_test_attempted_${spec.id}", false)
                .apply()
            destination
        } finally {
            endModelOperation()
        }
    }

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
                .putBoolean(smokeTestKey(spec), false)
                .putBoolean("smoke_test_attempted_${spec.id}", false)
                .apply()
            destination
        }.getOrNull().also {
            if (it == null) temporary.delete()
        }
    }

    fun markSmokeTestPassed(spec: LocalModelSpec = selectedModel()) {
        preferences.edit().putBoolean(smokeTestKey(spec), true).apply()
    }

    fun clearSmokeTest() {
        preferences.edit().putBoolean(smokeTestKey(selectedModel()), false).apply()
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
                // download hash. This makes “import your own compatible Gemma” supported.
                check(temporary.renameTo(destination)) { "Unable to finalize model file." }
                val fingerprint = fingerprintKey(spec)
                preferences.edit()
                    .putString(fingerprint, actualSha256)
                    .putLong("${fingerprint}_length", destination.length())
                    .putLong("${fingerprint}_modified", destination.lastModified())
                    .putBoolean("${fingerprint}_invalid", false)
                    .putBoolean("${fingerprint}_enforce_catalog_hash", false)
                    .putBoolean(smokeTestKey(spec), false)
                .putBoolean("smoke_test_attempted_${spec.id}", false)
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


}
