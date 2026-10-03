package com.battlesbudz.jarvis.v2.memory

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.Keep

@Keep
object MemorySensitivityPolicy {
    const val PRIVATE_COPY = "[Sensitive memory content is available only in a live unlocked view.]"
    fun durableCopy(text: String, sensitiveContext: Boolean): String = if (sensitiveContext) PRIVATE_COPY else text
    fun deliveryCopy(delivery: com.battlesbudz.jarvis.v2.voice.SpeechDelivery, sensitiveContext: Boolean) =
        if (sensitiveContext) delivery.copy(spans = delivery.spans.map { it.copy(text = PRIVATE_COPY) }) else delivery
    /** Stable release boundary: DTO construction stays inside the target APK. */
    fun publishSensitiveDelivery(controller: com.battlesbudz.jarvis.v2.voice.VoiceSessionController,
        callId: String, replyId: String, rawText: String): String {
        val delivery = deliveryCopy(com.battlesbudz.jarvis.v2.voice.SpeechDelivery(replyId, spans = listOf(
            com.battlesbudz.jarvis.v2.voice.DeliveredSpeechSpan(0, rawText, 0, 10, 10, true))), true)
        controller.updateDelivery(callId, delivery)
        return controller.currentTranscript().single { it.replyId == replyId }.delivery!!.spans.single().text
    }
    private val sensitive = Regex("\\b(?:health|diagnos\\w*|medication|therapy|medical|bank|salary|income|balance|financial|religion|sexual|address|identity|passport)\\b", RegexOption.IGNORE_CASE)
    fun classify(text: String, suggested: MemorySensitivity): MemorySensitivity =
        if (suggested == MemorySensitivity.RESTRICTED || sensitive.containsMatchIn(text) || MemoryPolicy.containsRawRestrictedContent(text))
            MemorySensitivity.RESTRICTED else MemorySensitivity.NORMAL
    fun mayDisclose(record: MemoryRecord, unlocked: Boolean): Boolean =
        record.source.sensitivity == MemorySensitivity.NORMAL || unlocked
    fun unlocked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)
        ?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true
}

/** The visible surfaces discard their composition when lock notification arrives. */
@Composable
fun memoryDisclosureUnlocked(): Boolean {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(MemorySensitivityPolicy.unlocked(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                unlocked = intent?.action != android.content.Intent.ACTION_SCREEN_OFF && MemorySensitivityPolicy.unlocked(context ?: return)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return unlocked && MemorySensitivityPolicy.unlocked(context)
}
