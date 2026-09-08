package com.battlesbudz.jarvis.v2.voice

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** Observes window TYPES only; never accesses event text, window roots or typed content. */
class KeyboardMicrophoneService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val releaseKeyboard = Runnable { MicrophoneHandoff.keyboardVisible = false }
    private val refresh = Runnable { inspectWindows() }
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { inspectWindows() }
    }
    override fun onServiceConnected() {
        MicrophoneHandoff.keyboardHelperConnected.value = true
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        inspectWindows()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    private fun inspectWindows() {
        handler.removeCallbacks(releaseKeyboard)
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val keyboard = interactive && windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (keyboard) MicrophoneHandoff.keyboardVisible = true
        else if (!interactive) MicrophoneHandoff.keyboardVisible = false
        else handler.postDelayed(releaseKeyboard, 750) // Avoid re-grabbing between keyboard panels.
    }
    override fun onInterrupt() { inspectWindows() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        MicrophoneHandoff.keyboardVisible = false
        MicrophoneHandoff.keyboardHelperConnected.value = false
        super.onDestroy()
    }
}
