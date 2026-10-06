package com.battlesbudz.jarvis.v2.voice

import android.os.Looper
import com.battlesbudz.jarvis.v2.ai.audio.GemmaStreamingArtifactStore
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.NativeAudioArtifactLease
import com.google.ai.edge.litertlm.NativeAudioIdentity
import com.google.ai.edge.litertlm.NativeAudioOwner
import com.google.ai.edge.litertlm.NativeAudioWorker
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Turn-owned adapter. Capture observes retained bytes; only a joined complete
 * request can expose immutable SDK content. No microphone or tool authority. */
internal class GemmaStreamingAudioCapture(
    private val artifact: GemmaStreamingArtifactStore.Lease,
    turnId: String,
    turnIsCurrent: () -> Boolean,
) : RetainedPcmObserver {
    private val valid = AtomicBoolean(true)
    private val turnContext = AtomicReference<(() -> Boolean)?>(turnIsCurrent)
    private val generation = generations.incrementAndGet().also { check(it > 0) }
    private val identityPrefix = MessageDigest.getInstance("SHA-256").digest(turnId.toByteArray(Charsets.UTF_8))
        .take(20).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val nativeArtifact = object : NativeAudioArtifactLease {
        override val immutablePath get() = artifact.file.path
        override fun checkImmutableAndLive() = artifact.checkImmutableAndLive()
    }
    private val worker = RetainedPcmEncoderWorker<Content.SealedAudioEmbeddings>(
        createEncoder = { candidate ->
            artifact.checkImmutableAndLive()
            val workerToken = NativeAudioWorker.bindCurrentThread {
                check(Looper.myLooper() != Looper.getMainLooper()) { "native_audio_on_ui_thread" }
                check(Thread.currentThread().name == "JarvisNativeAudioEncoder") { "native_audio_not_owned_worker" }
            }
            // Allocate the app holder before publishing any native registry handle.
            val adapter = EncoderAdapter()
            adapter.native = NativeAudioOwner.createOnWorker(workerToken, nativeArtifact,
                NativeAudioIdentity("${identityPrefix}_$candidate", generation))
            adapter
        },
        isGenerationCurrent = { valid.get() && turnContext.get()?.invoke() == true },
    )

    override fun onPcm(retainedPcm16: ByteArray) = worker.onPcm(retainedPcm16)
    override fun onCandidateDiscarded() = worker.onCandidateDiscarded()
    override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) = worker.onCaptureInvalidated(reason)

    /** WavEncoder's canonical mono16k PCM16 header is checked, never guessed. */
    suspend fun sealAfterCaptureJoined(wav: ByteArray): Content.SealedAudioEmbeddings {
        check(wav.size > 44 && wav.size % 2 == 0) { "native_audio_invalid_capture_wav" }
        val pcm = wav.copyOfRange(44, wav.size)
        try {
            check(WavEncoder.pcm16Mono(pcm, 16_000).contentEquals(wav)) { "native_audio_noncanonical_capture_wav" }
            check(valid.get() && turnContext.get()?.invoke() == true) { "native_audio_stale_capture" }
            return worker.sealAfterCaptureJoined(pcm).content
        } finally { pcm.fill(0) }
    }

    fun requestCancel() { valid.set(false); turnContext.set(null); worker.requestCancel() }

    /** Caller invokes under NonCancellable, then releases the artifact/model lease
     * only on true. False is permanent quarantine for this turn. */
    suspend fun closeAndDrain(): Boolean {
        requestCancel()
        val safe = worker.closeAndDrain()
        if (safe) artifact.close()
        return safe
    }

    private class EncoderAdapter : RetainedPcmEncoderWorker.Encoder<Content.SealedAudioEmbeddings> {
        lateinit var native: NativeAudioOwner
        override fun append(pcm: FloatArray) = native.appendOnWorker(pcm)
        override fun seal(): RetainedPcmEncoderWorker.Sealed<Content.SealedAudioEmbeddings> {
            val result = native.sealOnWorker()
            return RetainedPcmEncoderWorker.Sealed(result.provenance.pcmSampleCount, result.content)
        }
        override fun requestCancel() { native.requestCancel() }
        override fun closeOnWorker(timeoutMs: Long) = native.closeOnWorker(timeoutMs)
    }

    companion object { private val generations = AtomicLong() }
}
