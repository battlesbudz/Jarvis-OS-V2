package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class VoiceAudioSessionTest {
    private class Source : AudioInput {
        private data class Packet(val pcm: ByteArray, val at: Long, val seen: CompletableDeferred<Unit>)
        private val packets = Channel<Packet>(Channel.UNLIMITED)
        var starts = 0; var stops = 0
        override val sampleRateHz = 16000
        override val channelCount = 1
        override var lastChunkCaptureTimeMs: Long? = null
        override suspend fun start() { starts++ }
        override suspend fun stop() { stops++; packets.cancel() }
        override fun chunks() = flow {
            for (packet in packets) {
                lastChunkCaptureTimeMs = packet.at
                emit(packet.pcm)
                packet.seen.complete(Unit)
            }
        }
        suspend fun push(value: Int, at: Long = value * 100L) {
            val seen = CompletableDeferred<Unit>()
            packets.send(Packet(ByteArray(3200) { value.toByte() }, at, seen))
            withTimeout(1000) { seen.await() }
        }
    }
    @Test fun finalizingConsumerRetainsRecorderAndReplaysOnlyUnconsumedFrames() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this)
        try {
            val command = session.borrow("command"); command.start()
            source.push(1); source.push(2)
            assertEquals(1, command.chunks().first()[0].toInt()); command.stop()
            assertEquals(0, source.stops)
            source.push(3) // Recognition sealing can run while this audio is retained.
            val reply = session.borrow("reply"); reply.start()
            assertEquals(listOf(2, 3), reply.chunks().take(2).map { it[0].toInt() }.toList())
            reply.stop(); assertEquals(1, source.starts)
        } finally { session.close() }
        session.close(); assertEquals(1, source.stops)
    }
    @Test fun recognitionEndpointLeavesNextSpeechForTheReplyReader() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this)
        val input = session.borrow("command")
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = SpeechDecision(true, 0.99f)
            override fun close() = Unit
        }
        val transcriber = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Hello Jarvis."
            override fun finish() = "Hello Jarvis."
            override fun close() = Unit
        }
        val capture = AudioTurnCapture(input, this, { detector }, createTranscriber = { transcriber })
        try {
            capture.start(); source.push(1); yield()
            capture.finishNow(); source.push(2)
            assertTrue(withTimeout(1000) { capture.awaitTurnCompletion() })
            source.push(3) // Arrives after endpoint, before the command consumer is returned.
            capture.stop()
            assertEquals(0, source.stops)
            val reply = session.borrow("reply"); reply.start()
            assertEquals(3, withTimeout(1000) { reply.chunks().first()[0].toInt() })
            reply.stop()
        } finally { capture.stop(); session.close() }
        assertEquals(1, source.starts); assertEquals(1, source.stops)
    }
    @Test fun immediateFollowupReplaysOnlyPostPlaybackAudioEvenIfKeywordListenerConsumedIt() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this)
        try {
            val reply = session.borrow("reply"); reply.start(); source.push(1, 100); source.push(2, 200)
            reply.chunks().take(2).collect(); reply.stop()
            val followup = session.borrow("followup", replayAfterMs = 200); followup.start()
            assertEquals(2, followup.chunks().first()[0].toInt()); followup.stop()
        } finally { session.close() }
    }
    @Test fun gainMutationCannotCorruptRetainedRawAudio() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this)
        try {
            val first = session.borrow("first"); first.start(); source.push(4)
            first.chunks().first().fill(99); first.stop()
            val second = session.borrow("second", 400); second.start()
            assertEquals(4, second.chunks().first()[0].toInt()); second.stop()
        } finally { session.close() }
    }
    @Test fun idleHandoffOverflowRejectsPartialCommand() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this, historyMs = 100)
        try {
            val first = session.borrow("first"); first.start(); first.stop()
            source.push(1); source.push(2)
            try { session.borrow("next").start(); fail("Expected bounded capture rejection") }
            catch (_: AudioBacklogException) { }
        } finally { session.close() }
    }
    @Test fun hardwareHandoffPropagatesReasonAndNewSessionAcquiresCleanly() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this)
        val input = session.borrow("reply"); input.start()
        val error = IllegalStateException("external microphone")
        session.close(error)
        try { input.chunks().first(); fail("Expected microphone failure") }
        catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery can copy the exception in debug/test builds.
            assertEquals(error.javaClass, actual.javaClass)
            assertEquals(error.message, actual.message)
        }
        assertFalse(session.usable); assertEquals(1, source.stops)
        val replacementSource = Source(); val replacement = VoiceAudioSession(replacementSource, this)
        try {
            val resumed = replacement.borrow("command"); resumed.start(); replacementSource.push(7)
            assertEquals(7, resumed.chunks().first()[0].toInt()); resumed.stop()
        } finally { replacement.close() }
    }
    @Test fun secondConsumerCannotCompeteAndCancelledCollectorCanReturnLease() = runBlocking {
        val source = Source(); val session = VoiceAudioSession(source, this)
        try {
            val first = session.borrow("first"); first.start()
            try { session.borrow("second").start(); fail("Concurrent reader accepted") }
            catch (_: IllegalStateException) { }
            val reading = launch(start = CoroutineStart.UNDISPATCHED) { first.chunks().collect() }
            reading.cancelAndJoin(); first.stop()
            val next = session.borrow("next"); next.start(); source.push(8)
            assertEquals(8, next.chunks().first()[0].toInt()); next.stop()
        } finally { session.close() }
    }
}
