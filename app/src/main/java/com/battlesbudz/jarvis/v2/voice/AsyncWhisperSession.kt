package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Single native worker. Live input never waits for decode; busy workers skip obsolete windows.
 * Windows overlap and grow with this bounded utterance, so partials do not duplicate boundary words. */
internal class AsyncWhisperSession(
    private val decode: (ByteArray) -> String,
    private val release: () -> Unit,
    private val log: (String) -> Unit = {}
) : StreamingTranscriber {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "jarvis-whisper").apply { isDaemon = true } }
    private var audio = RollingAudioBuffer(AudioFormat(16000), maxDurationMs = 25000)
    private var active = false
    private var total = 0L
    private var scheduled = 0L
    private var inFlight: Future<*>? = null
    @Volatile private var latest = Result(-1, "")
    @Volatile private var stable = ""
    @Volatile private var failure: Throwable? = null
    private var closed = false
    private var sealed = false
    private data class Result(val end: Long, val text: String)
    override val noTextSilenceMs: Long get() = 900
    override fun observeSpeech(speech: Boolean) {
        if (speech && !active) {
            val tail = audio.snapshot().takeLast(38400).toByteArray()
            audio.clear(); audio.append(tail); total = tail.size.toLong(); active = true
        }
    }
    override fun accept(pcm: ByteArray): String {
        check(!closed && !sealed)
        failure?.let { throw IllegalStateException("Whisper background decoding failed", it) }
        audio.append(pcm); total += pcm.size
        if (active && total >= 48000 && total - scheduled >= 38400 && inFlight?.isDone != false) {
            val snapshot = audio.snapshot(); val end = total
            scheduled = end
            inFlight = worker.submit {
                try {
                    val began = System.nanoTime()
                    val text = decode(snapshot)
                    stable = agreeingPrefix(latest.text, text)
                    latest = Result(end, text)
                    log("whisper_partial audioMs=${snapshot.size / 32} decodeMs=${(System.nanoTime()-began)/1_000_000} stableChars=${stable.length} pendingWindows=0")
                } catch (error: Throwable) { failure = error }
            }
        }
        return stable
    }
    override fun finish(): String {
        check(!closed); sealed = true
        val snapshot = audio.snapshot(); val end = total
        val queuedAt = System.nanoTime()
        return worker.submit<String> {
            val queueWaitMs = (System.nanoTime() - queuedAt) / 1_000_000
            failure?.let { throw IllegalStateException("Whisper background decoding failed", it) }
            val reused = latest.end == end
            val began = System.nanoTime()
            val result = if (reused) latest.text else decode(snapshot)
            log("whisper_final reused=$reused queueWaitMs=$queueWaitMs audioMs=${snapshot.size / 32} decodeMs=${(System.nanoTime()-began)/1_000_000}")
            result
        }.get()
    }
    override fun recover(pcm: ByteArray): String = worker.submit<String> { decode(pcm) }.get()
    override fun close() {
        if (closed) return
        closed = true
        // Never release a recognizer underneath an in-flight JNI call.
        try { worker.submit { release() }.get() }
        finally { worker.shutdown(); audio.clear() }
    }
    companion object {
        fun agreeingPrefix(previous: String, current: String): String {
            val a = previous.trim().split(Regex("\\s+")); val b = current.trim().split(Regex("\\s+"))
            var count = 0
            while (count < minOf(a.size, b.size) && a[count].equals(b[count], ignoreCase = true)) count++
            return b.take(count).joinToString(" ").trim()
        }
    }
}
