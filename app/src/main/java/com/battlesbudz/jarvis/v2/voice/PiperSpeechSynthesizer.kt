package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

/**
 * Converts text to validated PCM on the producer's native dispatcher.
 *
 * The caller exclusively owns [tts] and releases its lease only after generation returns.
 * This collaborator never acquires, releases, queues or plays the model's output. The
 * existing Java callback remains the JNI boundary; exceptions leave it only after JNI returns.
 */
internal class PiperSpeechSynthesizer(
    private val tts: OfflineTts,
    private val generation: GenerationConfig,
    private val owner: CoroutineContext,
    private val stopped: () -> Boolean,
    private val answerTextReady: () -> Boolean,
    private val log: (String) -> Unit
) {
    class FillerSuperseded : RuntimeException()

    fun synthesize(text: String, optionalFiller: Boolean = false): SpeechAudio {
        owner.ensureActive()
        val started = System.nanoTime()
        log("tts_generation_started chars=${text.length} preview=${text.take(80)} api=generateWithConfig")
        val generated = if (optionalFiller) {
            // No PCM is played or cached until optional preparation completes.
            val callback = SherpaPcmCallback {
                if (stopped() || !owner.isActive || answerTextReady()) 0 else 1
            }
            val result = tts.generateWithConfigAndCallback(text, generation, callback)
            callback.failure?.let { throw it }
            owner.ensureActive()
            if (stopped() || answerTextReady()) throw FillerSuperseded()
            result
        } else tts.generateWithConfig(text, generation)
        owner.ensureActive()
        val rate = generated.sampleRate
        val pcm = SynthesizedSpeechPcm.fromModel(generated.samples, rate)
        log("tts_pcm_level rms=${pcm.rms} peak=${pcm.peak} frames=${pcm.samples.size} nonFinite=0 clipped=${pcm.clippedSamples} " +
            "leadingSilenceMs=${pcm.leadingSilenceFrames * 1000L / rate} trailingSilenceMs=${pcm.trailingSilenceFrames * 1000L / rate}")
        return SpeechAudio(text, rate, pcm.samples, (System.nanoTime() - started) / 1_000_000)
    }
}
