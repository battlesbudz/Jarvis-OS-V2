package com.battlesbudz.jarvis.v2.voice

/** One native owner; segment boundaries are internal and never complete a conversational turn. */
class SegmentedTranscriber(
    private val create: () -> StreamingTranscriber,
    private val log: (String) -> Unit = {}
) : StreamingTranscriber {
    private var current: StreamingTranscriber? = create()
    private val text = UtteranceAccumulator()
    private val overlap = RollingAudioBuffer(maxDurationMs = 1200)
    private var segmentBytes = 0L
    private var speech = false
    private var silenceBytes = 0L
    private var segmentHadSpeech = false
    private var replay = byteArrayOf()
    private var closed = false
    private var sealed = false
    private var last = ""
    private var finalSegment = ""
    val segments get() = text.segments
    val issue get() = text.issue
    override val noTextSilenceMs get() = current?.noTextSilenceMs ?: 3000L
    override fun observeSpeech(speech: Boolean) {
        this.speech = speech
        if (speech) segmentHadSpeech = true
        current?.observeSpeech(speech)
    }
    override fun accept(pcm: ByteArray): String {
        check(!closed && !sealed)
        val engine = current ?: create().also {
            current = it
            if (replay.isNotEmpty()) { it.observeSpeech(true); it.accept(replay) }
            segmentBytes = replay.size.toLong(); replay = byteArrayOf()
            it.observeSpeech(speech)
        }
        overlap.append(pcm)
        segmentBytes += pcm.size
        silenceBytes = if (speech) 0 else silenceBytes + pcm.size
        val partial = engine.accept(pcm)
        last = text.partial(partial)
        val quietBoundary = segmentBytes >= 15 * 32000 && silenceBytes >= 6400
        val forcedBoundary = segmentBytes >= 22 * 32000
        if (segmentHadSpeech && (quietBoundary || forcedBoundary)) {
            val finalized = engine.finish()
            val hard = !quietBoundary
            text.commit(finalized, nextOverlaps = hard)
            current = null
            engine.close() // Release before constructing the next stream, including Whisper workers.
            replay = if (hard) overlap.snapshot() else byteArrayOf()
            overlap.clear(); silenceBytes = 0; segmentHadSpeech = false
            last = text.partial("")
            log("asr_segment_committed index=$segments chars=${last.length} overlapMs=${replay.size / 32} " +
                "issue=$issue turnComplete=false")
        }
        return last
    }
    override fun finish(): String {
        check(!closed)
        if (!sealed) {
            sealed = true
            last = if (current == null) last else {
                finalSegment = current!!.finish()
                if (segments == 0 && TranscriptContent.isSoundOnly(finalSegment)) finalSegment
                else text.finish(finalSegment, speechExpected = segments > 0 && segmentHadSpeech)
            }
        }
        return last
    }
    override fun recover(pcm: ByteArray): String = if (segments == 0) current?.recover(pcm).orEmpty() else ""
    fun resumeAfterEndpoint() {
        check(sealed && !closed)
        val owned = current
        if (owned != null) {
            text.commit(finalSegment, nextOverlaps = false)
            current = null
            owned.close()
        }
        sealed = false; replay = byteArrayOf(); segmentBytes = 0
        segmentHadSpeech = false; silenceBytes = 0; overlap.clear()
    }
    override fun close() {
        if (closed) return
        closed = true
        val owned = current; current = null
        try { owned?.close() } finally { overlap.clear(); replay = byteArrayOf() }
    }
}
