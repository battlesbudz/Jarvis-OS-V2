package com.battlesbudz.jarvis.v2.voice

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android microphone adapter. It emits bounded PCM chunks and owns only the
 * microphone lifecycle; turn detection and model decisions stay in the voice coordinator.
 */
class AndroidAudioInput(
    private val scope: CoroutineScope,
    private val format: AudioFormat = AudioFormat(),
    private val chunkSamples: Int = 1_600
) : AudioInput {
    override val sampleRateHz: Int = format.sampleRateHz
    override val channelCount: Int = format.channelCount

    private val emittedChunks = Channel<ByteArray>(64)
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    override fun chunks(): Flow<ByteArray> = emittedChunks.receiveAsFlow()

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
        val bufferSize = maxOf(minBuffer, format.sampleRateHz * 2)
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
            check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start recording." }
        } catch (error: Throwable) {
            created.release()
            throw error
        }
        val ready = CompletableDeferred<Unit>()
        recorder = created
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val pcm = ByteArray(chunkSamples * 2)
                    var capturedBytes = 0
                    while (isActive) {
                        val count = created.read(pcm, 0, pcm.size)
                        if (count > 0) {
                            check(emittedChunks.trySend(pcm.copyOf(count)).isSuccess) {
                                "Microphone processing fell behind: audio queue is full."
                            }
                            capturedBytes += count
                            // Retain startup audio while allowing the hardware capture path to warm up.
                            if (capturedBytes >= format.sampleRateHz * 2 * 300 / 1000) ready.complete(Unit)
                        }
                        else if (count < 0) error("The microphone stopped recording unexpectedly.")
                    }
                }
            } catch (cancelled: CancellationException) {
                ready.cancel()
                throw cancelled
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                emittedChunks.close(error)
            } finally {
                runCatching { created.stop() }
                created.release()
                if (recorder === created) recorder = null
            }
        }
        try { withTimeout(5000) { ready.await() } }
        catch (error: Throwable) { stop(); throw error }
    }

    override suspend fun stop() {
        val job = captureJob
        job?.cancel()
        // Unblock read(), then wait for its sole owner to release the recorder.
        recorder?.let { runCatching { it.stop() } }
        job?.join()
        captureJob = null
        emittedChunks.cancel()
    }
}
