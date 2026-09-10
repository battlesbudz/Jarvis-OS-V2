package com.battlesbudz.jarvis.v2.ai

/** One bounded, process-local sample. Audio is never written to benchmark preferences. */
class GemmaLatencySample private constructor(
    val transcript: String,
    val contextPrompt: String,
    val audio: ByteArray,
    val sourceTurn: String,
    val capturedAtMs: Long
) {
    companion object {
        fun capture(transcript: String, contextPrompt: String, audio: ByteArray,
                    sourceTurn: String, capturedAtMs: Long): GemmaLatencySample? {
            if (transcript.isBlank() || transcript.length > 4000 || contextPrompt.length > 32_000 ||
                audio.size !in 44..1_048_576) return null
            return GemmaLatencySample(transcript, contextPrompt, audio.copyOf(), sourceTurn, capturedAtMs)
        }
    }
}

internal object GemmaLatencyCases {
    enum class Mode { SHORT_TEXT, CONTEXT_TEXT, CONTEXT_AUDIO }
    data class Case(val mode: Mode, val prompt: String, val audio: ByteArray?)
    fun cases(sample: GemmaLatencySample, pass: Int): List<Case> {
        val instruction = "\nFor this read-only benchmark, answer in one short sentence. Do not call tools."
        val cases = listOf(
            Case(Mode.SHORT_TEXT, "User request: ${sample.transcript}" + instruction, null),
            Case(Mode.CONTEXT_TEXT, sample.contextPrompt + instruction, null),
            Case(Mode.CONTEXT_AUDIO, sample.contextPrompt + instruction, sample.audio)
        )
        // Rotate starting modes so each mode is first, middle and last over three passes.
        val offset = (pass - 1).mod(cases.size)
        return cases.drop(offset) + cases.take(offset)
    }
}
