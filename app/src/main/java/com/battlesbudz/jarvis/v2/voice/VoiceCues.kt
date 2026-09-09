package com.battlesbudz.jarvis.v2.voice

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Distinct mode cues, serialized and released on cancellation. Uses the user's media volume. */
object VoiceCues {
    enum class Cue { COMMAND_READY, WAKE_LISTENING }
    private val playback = Mutex()
    suspend fun play(cue: Cue, log: (String) -> Unit = {}) = playback.withLock {
        var tone: ToneGenerator? = null
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            val type = if (cue == Cue.COMMAND_READY) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
            // ACK: two bright pulses. NACK: a single lower, rounded tone.
            val duration = if (cue == Cue.COMMAND_READY) 320 else 280
            check(tone.startTone(type, duration)) { "Audio cue did not start" }
            delay(duration + 50L)
            log("Voice cue played: $cue gain=100 durationMs=$duration")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log("Voice cue unavailable: $cue ${error.message}") }
        finally { tone?.release() }
    }
    internal suspend fun playAcknowledgement(audio: SpeechAudio, stopped: () -> Boolean,
                                    paused: () -> Boolean, log: (String) -> Unit) {
        var track: android.media.AudioTrack? = null
        try {
            if (stopped() || paused()) return
            track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(audio.sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(audio.pcm.size * 2).build()
            check(track.write(audio.pcm, 0, audio.pcm.size) == audio.pcm.size)
            if (stopped() || paused()) return
            track.play()
            log("acknowledgement_playback_started text=One moment. separateFromAnswer=true")
            val started = System.nanoTime()
            var confirmed = false
            while (!stopped() && !paused() && track.playbackHeadPosition.toLong() < audio.pcm.size &&
                (System.nanoTime() - started) / 1_000_000 < 4000) {
                if (!confirmed && track.playbackHeadPosition > 0) {
                    confirmed = true
                    log("acknowledgement_playback_confirmed playbackHead=${track.playbackHeadPosition} separateFromAnswer=true")
                }
                delay(15)
            }
            log("acknowledgement_playback_finished frames=${track.playbackHeadPosition}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log("acknowledgement_unavailable reason=${error.message}") }
        finally { track?.let { runCatching { it.pause() }; it.release() } }
    }

}
