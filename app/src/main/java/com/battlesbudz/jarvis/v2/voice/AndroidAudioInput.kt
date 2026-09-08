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
    private val chunkSamples: Int = 1_600,
    private val audioManager: android.media.AudioManager? = null,
    private val onWaiting: (Boolean) -> Unit = {},
    private val dictation: Boolean = false,
    private val onLevel: (Float) -> Unit = {}
) : AudioInput {
    override val sampleRateHz: Int = format.sampleRateHz
    override val channelCount: Int = format.channelCount

    private val emittedChunks = Channel<ByteArray>(64)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile var ownsRecorder = false
        private set
    private var captureJob: Job? = null

    override fun chunks(): Flow<ByteArray> = emittedChunks.receiveAsFlow()

    override suspend fun start() {
        if (captureJob?.isActive == true) return
        check(format.channelCount == 1 && format.bitsPerSample == 16) {
            "AndroidAudioInput currently requires PCM16 mono input."
        }
        var waiting = false
        while (busy(null)) {
            if (!waiting) { onWaiting(true); waiting = true }
            kotlinx.coroutines.delay(250)
        }
        if (waiting) onWaiting(false)
        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "The microphone could not be initialized." }
        val bufferSize = maxOf(minBuffer, format.sampleRateHz * 2)
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(format.sampleRateHz)
                .setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferSize)
        if (android.os.Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false)
        val created = builder.build()
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
        ownsRecorder = true
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val pcm = ByteArray(chunkSamples * 2)
                    val assembler = PcmChunkAssembler(pcm.size)
                    var capturedBytes = 0
                    while (isActive) {
                        if (busy(created)) throw MicrophoneBusyException()
                        val count = created.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                        if (busy(created)) throw MicrophoneBusyException()
                        if (count == 0) { kotlinx.coroutines.delay(20); continue }
                        if (count > 0) {
                            var squares = 0.0
                            for (i in 0 until count - 1 step 2) {
                                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
                                squares += sample * sample
                            }
                            onLevel((kotlin.math.sqrt(squares / (count / 2).coerceAtLeast(1)) / 4000.0).toFloat().coerceIn(0f, 1f))
                            assembler.accept(pcm, count) { chunk ->
                                if (!emittedChunks.trySend(chunk).isSuccess) throw AudioBacklogException()
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
                if (recorder === created) { recorder = null; ownsRecorder = false }
                onLevel(0f)
            }
        }
        try { withTimeout(5000) { ready.await() } }
        catch (error: Throwable) { stop(); throw error }
    }

    private fun busy(record: AudioRecord?): Boolean {
        if (!dictation && MicrophoneHandoff.shouldYield) return true
        val manager = audioManager ?: return false
        return MicrophonePolicy.shouldYield(
            manager.activeRecordingConfigurations.size, record != null,
            record?.activeRecordingConfiguration?.isClientSilenced == true,
            manager.mode == android.media.AudioManager.MODE_IN_CALL ||
                manager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || manager.isMicrophoneMute)
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
