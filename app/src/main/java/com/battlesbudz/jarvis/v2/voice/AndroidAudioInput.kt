package com.battlesbudz.jarvis.v2.voice

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android microphone adapter. It emits bounded PCM chunks and owns only the
 * microphone lifecycle; wake-word and model decisions stay in SpeechPipeline.
 */
class AndroidAudioInput(
    private val scope: CoroutineScope,
    private val format: AudioFormat = AudioFormat(),
    private val chunkSamples: Int = 1_600
) : AudioInput {
    override val sampleRateHz: Int = format.sampleRateHz
    override val channelCount: Int = format.channelCount

    private val emittedChunks = MutableSharedFlow<Result<ByteArray>>(extraBufferCapacity = 4)
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    override fun chunks(): Flow<ByteArray> = emittedChunks.map { it.getOrThrow() }

    override suspend fun start() {
        if (captureJob?.isActive == true) return
        check(format.channelCount == 1 && format.bitsPerSample == 16) {
            "AndroidAudioInput currently requires PCM16 mono input."
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "The microphone could not be initialized." }
        val bufferSize = maxOf(minBuffer, chunkSamples * 2 * 2)
        val created = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            format.sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            error("The microphone could not be initialized.")
        }
        try {
            created.startRecording()
        } catch (error: Throwable) {
            created.release()
            throw error
        }
        recorder = created
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val pcm = ByteArray(chunkSamples * 2)
                    while (isActive) {
                        val count = created.read(pcm, 0, pcm.size)
                        if (count > 0) emittedChunks.emit(Result.success(pcm.copyOf(count)))
                        else if (count < 0) error("The microphone stopped recording unexpectedly.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                emittedChunks.emit(Result.failure(error))
            } finally {
                runCatching { created.stop() }
                created.release()
                if (recorder === created) recorder = null
            }
        }
    }

    override suspend fun stop() {
        val job = captureJob
        job?.cancel()
        // Unblock read(), then wait for its sole owner to release the recorder.
        recorder?.let { runCatching { it.stop() } }
        job?.join()
        captureJob = null
    }
}
