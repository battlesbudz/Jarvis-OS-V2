package com.battlesbudz.jarvis.v2.voice

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Distinct mode cues, serialized and released on cancellation. Uses the user's media volume. */
object VoiceCues {
    enum class Cue { COMMAND_READY, WAKE_LISTENING }
    private val playback = Mutex()
    suspend fun play(cue: Cue, log: (String) -> Unit = {}) = playback.withLock {
        var tone: ToneGenerator? = null
        try {
            tone = ToneGenerator(CallAudioRouting.stream, 100)
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
                                    paused: () -> Boolean, log: (String) -> Unit, mediaVolume: String = "unavailable",
                                    onStarted: () -> Unit = {}, speed: Float = 1f) {
        var track: android.media.AudioTrack? = null
        val evidenceStream = LiveCallAudioEvidence.newStream("filler")
        try {
            if (stopped() || paused()) return
            val firstSpeechFrame = FillerPcm.firstSpeechFrame(audio.pcm)
            check(firstSpeechFrame >= 0) { "Cached acknowledgement contains silence." }
            track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(CallAudioRouting.usage)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(audio.sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(audio.pcm.size * 2).build()
            check(track.write(audio.pcm, 0, audio.pcm.size) == audio.pcm.size)
            LiveCallAudioEvidence.recordOutput(evidenceStream, audio.pcm, 0, audio.pcm.size, audio.sampleRate)
            if (stopped() || paused()) return
            track.playbackParams = android.media.PlaybackParams().allowDefaults().setPitch(1f).setSpeed(speed)
            track.setVolume(1f)
            onStarted()
            track.play()
            log("acknowledgement_playback_started text=${audio.text} separateFromAnswer=true " +
                "usage=${track.audioAttributes.usage} speed=${track.playbackParams.speed} pitch=1.0 volume=$mediaVolume rms=${FillerPcm.rms(audio.pcm)} firstSpeechFrame=$firstSpeechFrame")
            val started = System.nanoTime()
            var confirmed = false
            while (!stopped() && !paused() && track.playbackHeadPosition.toLong() < audio.pcm.size &&
                (System.nanoTime() - started) / 1_000_000 < 4000) {
                if (!confirmed && track.playbackHeadPosition > firstSpeechFrame) {
                    confirmed = true
                    log("acknowledgement_speech_frames_rendered playbackHead=${track.playbackHeadPosition} " +
                        "routeType=${track.routedDevice?.type} routeId=${track.routedDevice?.id} " +
                        "usage=${track.audioAttributes.usage} separateFromAnswer=true acousticAudibility=not_measured")
                }
                LiveCallAudioEvidence.event("playback stream=$evidenceStream head=${track.playbackHeadPosition} state=${track.playState}")
                delay(15)
            }
            log("acknowledgement_playback_finished frames=${track.playbackHeadPosition}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log("acknowledgement_unavailable reason=${error.message}") }
        finally {
            track?.let { owned ->
                // Answer-priority cancellation fades only the filler, never the answer track.
                // Explicit stop still pauses immediately.
                if (!stopped() && !paused() && owned.playbackHeadPosition < audio.pcm.size) {
                    withContext(NonCancellable) {
                        for (volume in listOf(.75f, .5f, .25f, 0f)) {
                            runCatching { owned.setVolume(volume) }
                            delay(10)
                        }
                    }
                }
                runCatching { owned.pause() }
                owned.release()
            }
        }
    }

}
