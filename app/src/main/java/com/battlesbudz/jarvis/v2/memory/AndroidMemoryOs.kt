package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import android.app.KeyguardManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.io.File

/** Process-wide access to the app's private, device-local memory ledger. */
object AndroidMemoryOs {
    private data class Services(val memory: MemoryOs, val sources: SQLiteMemoryStore)
    @Volatile private var instance: Services? = null

    fun get(context: Context): MemoryOs = services(context).memory
    fun sources(context: Context): MemorySourceArchive = services(context).sources
    fun extraction(context: Context): MemoryExtractionJobs = services(context).sources
    fun extractionCommitted(context: Context) = services(context).memory.invalidateDisclosure()

    private fun services(context: Context): Services = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val directory = app.noBackupFilesDir
            val sources = SQLiteMemoryStore(File(directory, "memory-os.db"), File(directory, "memory-os.json"), canReadSourceText = {
                // Current lock state, never merely "unlocked since boot". Missing service fails closed.
                app.getSystemService(KeyguardManager::class.java)?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true
            })
            Services(MemoryOs(sources, clock = { System.currentTimeMillis() },
                canDiscloseSensitive = { MemorySensitivityPolicy.unlocked(app) }), sources).also { services ->
                // Live lock changes fence model/UI/TTS packets; no unlock-since-boot cache.
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: android.content.Intent?) { services.memory.invalidateDisclosure() }
                }
                val filter = android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_USER_PRESENT)
                }
                androidx.core.content.ContextCompat.registerReceiver(app, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                // Android may delay background work; read/write paths also purge and filter expiry.
                WorkManager.getInstance(app).enqueueUniquePeriodicWork("memory-source-expiry", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<MemoryArchiveMaintenanceWorker>(24, TimeUnit.HOURS).build())
                instance = services
            }
        }
    }
}
