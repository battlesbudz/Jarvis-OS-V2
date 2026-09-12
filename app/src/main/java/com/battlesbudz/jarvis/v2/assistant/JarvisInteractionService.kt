package com.battlesbudz.jarvis.v2.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.battlesbudz.jarvis.v2.actions.ExecutionResult

/** Android binds this only for the assistant selected by the user in system settings. */
class JarvisInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); active = this }
    override fun onShutdown() { if (active === this) active = null; super.onShutdown() }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
    companion object {
        private var active: JarvisInteractionService? = null
        fun launch(context: Context, intent: Intent, label: String): ExecutionResult? {
            val service = active ?: return null
            if (!isActiveService(context, ComponentName(context, JarvisInteractionService::class.java))) return null
            return runCatching {
                // User's explicit app command reaches this on the main thread, after validation.
                service.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ExecutionResult(true, "Opening $label through Jarvis assistant.")
            }.getOrElse { ExecutionResult(false, "Android rejected the assistant launch of $label: ${it.message}") }
        }
    }
}
