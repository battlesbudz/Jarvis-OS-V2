package com.battlesbudz.jarvis.v2.voice

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Checks window types/IDs and recorder activity only. Never reads typed text or window roots. */
class KeyboardMicrophoneService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var policy = KeyboardHandoffPolicy()
    private var receiverRegistered = false
    private var recordingCallbackRegistered = false
    private var lastState = ""
    private var lastKeyboardSeenAt: Long? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val poll = object : Runnable {
        override fun run() {
            if (!VoiceSessionUi.armed.value) return
            inspectWindows()
            handler.postDelayed(this, 250)
        }
    }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) { inspectWindows() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { inspectWindows() }
    }
    override fun onServiceConnected() {
        MicrophoneHandoff.keyboardHelperConnected.value = true
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        audio.registerAudioRecordingCallback(recordingCallback, handler)
        recordingCallbackRegistered = true
        scope.launch {
            VoiceSessionUi.armed.collect { armed ->
                handler.removeCallbacks(poll)
                policy = KeyboardHandoffPolicy()
                if (armed) poll.run() else MicrophoneHandoff.keyboardHolding = false
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        inspectWindows(if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) event.windowId else null)
    }
    private fun inspectWindows(clickedWindow: Int? = null) {
        if (!VoiceSessionUi.armed.value) return
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val keyboards = if (interactive) windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } else emptyList()
        val now = SystemClock.elapsedRealtime()
        if (keyboards.isNotEmpty()) lastKeyboardSeenAt = now
        // A keyboard can replace its dictation panel without actually being dismissed.
        val visible = interactive && (keyboards.isNotEmpty() || lastKeyboardSeenAt?.let { now - it < 750 } == true)
        val own = MicrophoneHandoff.backgroundRecorders.get()
        val other = (audio.activeRecordingConfigurations.size - own).coerceAtLeast(0)
        val recording = other > 0 || MicrophoneHandoff.dictationRequested
        val holding = policy.update(visible, recording,
            clickedWindow != null && keyboards.any { it.id == clickedWindow }, now)
        MicrophoneHandoff.keyboardVisible = visible
        MicrophoneHandoff.keyboardHolding = holding
        // Record changes, not each polling tick. Visible=true + holding=false is a valid resumed state.
        val state = "keyboard visible=$visible holding=$holding externalRecording=$recording"
        if (lastState != state) {
            MicrophoneHandoff.record(state)
            lastState = state
        }
    }
    override fun onInterrupt() { inspectWindows() }
    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        if (recordingCallbackRegistered) audio.unregisterAudioRecordingCallback(recordingCallback)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        MicrophoneHandoff.keyboardVisible = false
        MicrophoneHandoff.keyboardHolding = false
        MicrophoneHandoff.keyboardHelperConnected.value = false
        super.onDestroy()
    }
}
