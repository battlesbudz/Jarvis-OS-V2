package com.battlesbudz.jarvis.v2.presentation

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.chat.AssistantText
import com.battlesbudz.jarvis.v2.voice.JarvisModelSetupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity-scoped model management. Downloads use durable WorkManager work;
 * import and tests use the host's lifecycle scope. This never owns a voice job.
 */
internal class ModelSetupOperations(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: ModelStore,
    private val mainHandler: Handler,
    private val session: ModelSetupSession,
) {
    fun select(spec: LocalModelSpec): String? {
        if (session.busy()) return "End the Jarvis session and any tests before switching AI models."
        if (!store.tryBeginModelSelection(spec)) return "This model is downloading or a model test is running. Choose another installed model."
        return try {
            session.closeConversation(resetCharacters = true)
            store.selectModel(spec)
            session.record("AI model selected: ${spec.id}")
            null
        } catch (error: Exception) {
            "Could not switch models: ${error.message}"
        } finally {
            store.endModelOperation()
        }
    }

    fun delete(spec: LocalModelSpec): String? {
        if (session.busy()) return "End the Jarvis session and any tests before deleting AI models."
        if (!store.tryBeginModelOperation()) return "Wait for model setup or testing to finish."
        return try {
            session.closeConversation(resetCharacters = true)
            store.deleteModel(spec)
            session.record("AI model deleted: ${spec.id}")
            null
        } catch (error: Exception) {
            "Could not delete model: ${error.message}"
        } finally {
            store.endModelOperation()
        }
    }

    fun test(report: (String) -> Unit, onFinished: ((String) -> Unit)? = null) {
        if (!store.tryBeginModelOperation()) {
            val message = "A model test is still finishing. Please try again in a moment."
            report(message)
            onFinished?.invoke(message)
            return
        }
        val job = scope.launch(Dispatchers.Default) {
            mainHandler.post { report("Loading ${store.selectedModel().id}…") }
            var engine: LiteRtLmEngine? = null
            var succeeded = false
            var finalMessage: String? = null
            try {
                store.markSmokeTestStarted()
                check(store.verifyIntegrity(store.selectedModel())) {
                    "The selected model file changed or failed integrity verification. Re-import it."
                }
                session.closeConversation(resetCharacters = false)
                engine = LiteRtLmEngine(
                    store.selectedModel().id,
                    store.fileFor(store.selectedModel()).path,
                    context.cacheDir.path,
                    useGpu = store.selectedModel().recommendedGpu,
                    tools = if (store.selectedModel().supportsTools) MobileActionToolDefinitions.all() else emptyList(),
                    visionEnabled = false,
                    audioEnabled = false,
                )
                engine.initialize()
                val probe = engine.generate("Say hello in one short sentence.", onToken = {})
                check(AssistantText.forDisplay(probe.text).any { it.isLetterOrDigit() }) {
                    "The selected model loaded but did not return readable text."
                }
                succeeded = true
            } catch (error: Throwable) {
                finalMessage = "Selected model test failed: ${error.message ?: "unknown error"}"
            } finally {
                engine?.close()
                if (succeeded) {
                    store.markSmokeTestPassed()
                    finalMessage = "${store.selectedModel().id} initialized successfully."
                }
                finalMessage?.let { message ->
                    mainHandler.post {
                        report(message)
                        onFinished?.invoke(message)
                    }
                }
            }
        }
        job.invokeOnCompletion { store.endModelOperation() }
    }

    fun cancelDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(MODEL_SETUP_WORK_NAME)
    }

    fun download(
        spec: LocalModelSpec,
        onProgress: (Long, Long) -> Unit,
        report: (String) -> Unit,
        onFinished: (String) -> Unit,
    ) {
        val request = OneTimeWorkRequestBuilder<JarvisModelSetupWorker>()
            .setInputData(workDataOf("model_id" to spec.id,
                "prepare_voice" to (spec.id == store.selectedModel().id)))
            .addTag(MODEL_SETUP_WORK_NAME)
            .addTag("model:${spec.id}")
            .build()
        val workManager = WorkManager.getInstance(context)
        scope.launch {
            val observedId = withContext(Dispatchers.IO) {
                val priorActive = workManager.getWorkInfosForUniqueWork(MODEL_SETUP_WORK_NAME).get()
                    .firstOrNull { !it.state.isFinished }
                if (priorActive != null && "model:${spec.id}" !in priorActive.tags) return@withContext null
                workManager.enqueueUniqueWork(MODEL_SETUP_WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
                val infos = workManager.getWorkInfosForUniqueWork(MODEL_SETUP_WORK_NAME).get()
                if (infos.any { it.id == request.id }) request.id else priorActive?.id
            }
            if (observedId == null) {
                onFinished("Another download is running. Let it finish or cancel it before downloading this model.")
                return@launch
            }
            var terminal: WorkInfo? = null
            workManager.getWorkInfosForUniqueWorkFlow(MODEL_SETUP_WORK_NAME)
                .takeWhile { infos ->
                    // KEEP may attach to active work; a prior completed request is never this transfer.
                    val info = infos.firstOrNull { it.id == observedId }
                    if (info != null) {
                        val stage = info.progress.getString("stage")
                        val downloaded = info.progress.getLong("downloaded", 0L)
                        val total = info.progress.getLong("total", -1L)
                        if (!stage.isNullOrBlank()) report(stage)
                        if (downloaded > 0L) onProgress(downloaded, total)
                        if (info.state.isFinished) {
                            terminal = info
                            false
                        } else true
                    } else true
                }
                .collect { }
            val result = terminal
            if (result?.state == WorkInfo.State.SUCCEEDED) {
                // A background transfer must never close, load or test the user's current engine.
                onFinished("${spec.id} downloaded. Choose it when you're ready.")
            } else {
                val message = result?.outputData?.getString("error")
                    ?: if (result?.state == WorkInfo.State.CANCELLED) "Download cancelled. Saved progress can be resumed."
                    else "Jarvis model setup did not complete."
                report(message)
                onFinished(message)
            }
        }
    }

    fun importModel(uri: Uri, spec: LocalModelSpec, report: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = store.importModel(uri, spec)
            withContext(Dispatchers.Main) {
                if (result.isSuccess && spec.id == store.selectedModel().id) {
                    session.closeConversation(resetCharacters = false)
                }
                report(result.fold(
                    { "Model imported successfully." },
                    { error -> "Import failed: ${error.message ?: "unknown error"}" },
                ))
            }
        }
    }
}
