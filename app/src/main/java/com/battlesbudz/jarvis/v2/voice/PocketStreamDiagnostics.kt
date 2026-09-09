package com.battlesbudz.jarvis.v2.voice

import java.security.MessageDigest
import kotlin.math.abs

/** Observes delivered PCM only. Boundary measurements are clues, not a voice-quality verdict. */
internal class PocketStreamDiagnostics(private val session: String, private val log: (String) -> Unit) {
    private var calls = 0
    private var chunks = 0
    private var frames = 0L
    private var callChunks = 0
    private var previousSample: Short? = null
    private var maxWithinJump = 0
    private var maxAcrossJump = 0
    private val texts = mutableSetOf<String>()
    private var repeatedTextCalls = 0

    fun begin(index: Int, text: String, rate: Int) {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val repeated = !texts.add(hash)
        if (repeated) repeatedTextCalls++
        calls++
        callChunks = 0
        log("pocket_stream_trace event=submit session=$session index=$index call=$calls " +
            "textSha256=$hash repeatedText=$repeated chars=${text.length} " +
            "startFrame=$frames sampleRate=$rate pcmTimeMs=${frames * 1000 / rate} " +
            "stateEvidence=native_source_contract lm=copy_voice_prompt_each_call " +
            "decoderAndRng=retain_for_session nativeResetObserved=unavailable")
    }

    fun chunk(index: Int, pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val jump = previousSample?.let { abs(pcm.first().toInt() - it.toInt()) } ?: 0
        if (callChunks == 0) {
            maxAcrossJump = maxOf(maxAcrossJump, jump)
            log("pocket_stream_trace event=first_pcm session=$session index=$index " +
                "startFrame=$frames frames=${pcm.size} joinDeltaPcm16=$jump")
        } else maxWithinJump = maxOf(maxWithinJump, jump)
        previousSample = pcm.last()
        frames += pcm.size
        chunks++
        callChunks++
    }

    fun finish(index: Int) {
        log("pocket_stream_trace event=call_end session=$session index=$index " +
            "callbacks=$callChunks endFrame=$frames")
    }

    fun summary(underruns: Int, gapMs: Long, completed: Boolean) {
        log("pocket_stream_trace event=summary session=$session submissions=$calls callbacks=$chunks " +
            "frames=$frames repeatedTextCalls=$repeatedTextCalls " +
            "maxWithinCallJoinDeltaPcm16=$maxWithinJump maxAcrossCallJoinDeltaPcm16=$maxAcrossJump " +
            "underruns=$underruns estimatedSupplyGapMs=$gapMs completed=$completed " +
            "scope=answer_callback_pcm excludes=fillers qualityCause=not_determined")
    }
}
