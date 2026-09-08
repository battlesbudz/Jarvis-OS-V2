package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.first

/** Service-owned callbacks plus bounded polling. No keyboard or text-field inspection. */
class MicrophoneInterruptionMonitor(context: Context, private val changed: (Boolean, String) -> Unit) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val policy = MicrophoneInterruptionPolicy()
    private var active = true
    private var lastDetails = ""
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) { inspect() }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            inspect()
            handler.postDelayed(this, 500)
        }
    }
    init {
        audio.registerAudioRecordingCallback(callback, handler)
        poll.run()
    }
    private fun inspect() {
        if (!active) return
        val own = MicrophoneHandoff.backgroundRecorders.get()
        val recordings = audio.activeRecordingConfigurations.size
        val external = (recordings - own).coerceAtLeast(0)
        val communication = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val muted = audio.isMicrophoneMute
        val busy = external > 0 || communication || muted || MicrophoneHandoff.dictationRequested || MicrophoneHandoff.ownRecorderSilenced
        val held = policy.update(busy, SystemClock.elapsedRealtime())
        val details = "external=$external own=$own silenced=${MicrophoneHandoff.ownRecorderSilenced} communication=$communication muted=$muted dictation=${MicrophoneHandoff.dictationRequested}"
        if (lastDetails != details) { MicrophoneHandoff.record("recording_state $details"); lastDetails = details }
        if (MicrophoneHandoff.interrupted.value != held) {
            MicrophoneHandoff.interrupted.value = held
            changed(held, details)
        }
    }
    override fun close() {
        active = false
        handler.removeCallbacksAndMessages(null)
        audio.unregisterAudioRecordingCallback(callback)
        MicrophoneHandoff.interrupted.value = false
    }
    companion object {
        suspend fun awaitAvailable() { MicrophoneHandoff.interrupted.first { !it } }
    }
}
