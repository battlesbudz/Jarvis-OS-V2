package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import java.io.File

/** Process-wide access to the app's private, device-local memory ledger. */
object AndroidMemoryOs {
    @Volatile private var instance: MemoryOs? = null

    fun get(context: Context): MemoryOs = instance ?: synchronized(this) {
        val directory = context.applicationContext.noBackupFilesDir
        instance ?: MemoryOs(SQLiteMemoryStore(File(directory, "memory-os.db"), File(directory, "memory-os.json")))
            .also { instance = it }
    }
}
