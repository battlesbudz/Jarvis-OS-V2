package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream

/** Fixed-duration, ungated PCM from the production input. Nothing is written to disk. */
object AudioPathDiagnostic {
    suspend fun record(input: AudioInput, durationMs: Int = 8000): ByteArray {
        require(durationMs in 1000..10000)
        val limit = durationMs * 32
        val bytes = ByteArrayOutputStream(limit)
        try {
            withTimeout(durationMs + 5000L) {
                input.start()
                input.chunks().takeWhile { chunk ->
                    bytes.write(chunk, 0, minOf(chunk.size, limit - bytes.size()))
                    bytes.size() < limit
                }.collect()
            }
            check(bytes.size() == limit) { "Microphone ended before the recording finished." }
            return bytes.toByteArray()
        } finally { withContext(NonCancellable) { input.stop() } }
    }

    suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        require(pcm.isNotEmpty() && pcm.size <= 320000 && pcm.size % 2 == 0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AndroidFormat.Builder().setSampleRate(16000)
                .setChannelMask(AndroidFormat.CHANNEL_OUT_MONO).setEncoding(AndroidFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
        try {
            check(track.state == AudioTrack.STATE_NO_STATIC_DATA || track.state == AudioTrack.STATE_INITIALIZED)
            check(track.write(pcm, 0, pcm.size) == pcm.size) { "Could not load the test recording for playback." }
            track.play()
            withTimeout(pcm.size / 32L + 3000) {
                while (track.playbackHeadPosition < pcm.size / 2) { ensureActive(); delay(25) }
            }
        } finally { runCatching { track.stop() }; track.release() }
    }
}
