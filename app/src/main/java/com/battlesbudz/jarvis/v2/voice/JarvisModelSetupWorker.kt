package com.battlesbudz.jarvis.v2.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.runBlocking

/**
 * Owns model setup outside the Activity lifecycle. A user can leave Jarvis,
 * lock the phone, or rotate the app while the large local models continue.
 */
class JarvisModelSetupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // A model download is long-running work. Keeping it foreground prevents
        // Android from killing it when the user opens the notification shade or
        // leaves Jarvis, while the unique WorkManager name prevents duplicates.
        setForeground(createForegroundInfo())

        val models = ModelStore(applicationContext)
        val voice = KokoroModelStore(applicationContext)
        var downloaded = 0L
        var total = -1L
        var stage = "Preparing local Jarvis…"
        val progressLock = Any()
        fun publishProgress() = runBlocking {
            setProgress(workDataOf(
                "stage" to stage,
                "downloaded" to downloaded,
                "total" to total
            ))
        }
        val gemma = models.downloadOrReuse(
            spec = ModelCatalog.gemma4E2b,
            onProgress = { bytes, length ->
                synchronized(progressLock) {
                    downloaded = bytes
                    total = length
                    if (!stage.contains("resumable parts", ignoreCase = true)) {
                        stage = "Gemma"
                    }
                    publishProgress()
                }
            },
            onStatus = { status ->
                synchronized(progressLock) {
                    stage = status
                    publishProgress()
                }
            }
        ).getOrElse { error ->
            return Result.failure(workDataOf("error" to (error.message ?: "Gemma setup failed.")))
        }
        check(gemma.isFile) { "Gemma setup did not produce a model file." }
        val kokoro = voice.downloadOrReuse(
            onProgress = { bytes, length ->
                synchronized(progressLock) {
                    val unpacking = stage.contains("Installing Kokoro", ignoreCase = true)
                    downloaded = bytes
                    total = length
                    stage = if (unpacking) "Kokoro unpacking" else "Kokoro voice"
                    publishProgress()
                }
            },
            onStatus = { status ->
                synchronized(progressLock) {
                    stage = status
                    if (status.contains("Installing Kokoro", ignoreCase = true)) {
                        downloaded = 0L
                        total = -1L
                    }
                    publishProgress()
                }
            }
        ).getOrElse { error ->
            return Result.failure(workDataOf("error" to (error.message ?: "Voice model setup failed.")))
        }
        check(kokoro.isDirectory) { "Kokoro setup did not produce a model directory." }
        try {
            AsrModelStore(applicationContext).ensureReady { status ->
                synchronized(progressLock) { stage = status; publishProgress() }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return Result.failure(workDataOf("error" to (error.message ?: "Speech recognition setup failed.")))
        }
        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Jarvis model setup",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Jarvis model setup")
            .setContentText("Preparing local models…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private companion object {
        const val CHANNEL_ID = "jarvis_model_setup"
        const val NOTIFICATION_ID = 4202
    }
}
