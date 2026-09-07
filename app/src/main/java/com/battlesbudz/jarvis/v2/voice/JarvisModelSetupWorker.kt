package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore

/**
 * Owns model setup outside the Activity lifecycle. A user can leave Jarvis,
 * lock the phone, or rotate the app while the large local models continue.
 */
class JarvisModelSetupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val models = ModelStore(applicationContext)
        val voice = KokoroModelStore(applicationContext)
        val gemma = models.downloadOrReuse(
            onProgress = { downloaded, total ->
                setProgress(workDataOf("stage" to "Gemma", "downloaded" to downloaded, "total" to total))
            },
            onStatus = { status -> setProgress(workDataOf("stage" to status)) }
        ).getOrElse { error ->
            return Result.failure(workDataOf("error" to (error.message ?: "Gemma setup failed.")))
        }
        check(gemma.isFile) { "Gemma setup did not produce a model file." }
        val kokoro = voice.downloadOrReuse(
            onProgress = { downloaded, total ->
                setProgress(workDataOf("stage" to "Kokoro voice", "downloaded" to downloaded, "total" to total))
            },
            onStatus = { status -> setProgress(workDataOf("stage" to status)) }
        ).getOrElse { error ->
            return Result.failure(workDataOf("error" to (error.message ?: "Voice model setup failed.")))
        }
        check(kokoro.isDirectory) { "Kokoro setup did not produce a model directory." }
        return Result.success()
    }
}
