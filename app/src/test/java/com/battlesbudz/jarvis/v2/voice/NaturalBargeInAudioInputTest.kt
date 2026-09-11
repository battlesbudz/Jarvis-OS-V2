package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class NaturalBargeInAudioInputTest {
    @Volatile private var clock = 0L
    private var confirmed = 0
    private var models = 0
    private var closedModels = 0
    private var keywordCalls = 0
    private val logs = mutableListOf<String>()
    private fun gate(text: String = "Actually open settings", reference: String = "The sky is blue",
                     speech: Boolean = true, budget: Boolean = true, keywordAt: Int = -1, keyword: String = "Hey_Jarvis",
                     failing: Boolean = false, chunks: Int = 30,
                     dispatcher: CoroutineDispatcher = Dispatchers.Unconfined, beforeFrame: (Int) -> Unit = {},
                     acceptAction: () -> Unit = {}, speechNow: () -> Boolean = { speech },
                     budgetNow: () -> Boolean = { budget }, backlogNow: () -> Long = { 0L }, onConfirmation: (Boolean) -> Unit = {}): NaturalBargeInAudioInput {
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override val lastChunkCaptureTimeMs get() = clock
            override val bufferedAudioMs get() = backlogNow()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun chunks() = flow {
                repeat(chunks) { index -> clock += 100; beforeFrame(index); emit(ByteArray(3200) { (index + 1).toByte() }); yield() }
            }
        }
        return NaturalBargeInAudioInput(input,
            createKeyword = { object : InterruptionKeywordDetector {
                override val ready = true
                override fun accept(pcm: ByteArray): String? {
                    keywordCalls++; return if (keywordCalls == keywordAt) keyword else null
                }
                override fun close() {}
            } },
            createVad = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = SpeechDecision(speechNow(), if (speechNow()) 0.99f else 0f)
                override fun close() {}
            } },
            createTranscriber = {
                models++
                object : StreamingTranscriber {
                    override fun accept(pcm: ByteArray): String { acceptAction(); if (failing) error("slow native"); return text }
                    override fun finish() = text
                    override fun close() { closedModels++ }
                }
            }, playing = { true }, reference = { reference }, hasPlaybackBudget = budgetNow,
            onConfirmed = { natural, _ ->
                if (natural) assertEquals(models, closedModels)
                onConfirmation(natural)
                confirmed++
            }, log = logs::add, nowMs = { clock }, dispatcher = dispatcher)
    }
    @Test fun sirMistakenForStopDoesNotCutOffPlayback() = runBlocking {
        val audio = gate(text = "Sir, I apologize for the interruption", reference = "Sir, I apologize for the interruption",
            speech = false, keywordAt = 10, keyword = "stop").chunks().toList()
        assertTrue(audio.isEmpty()); assertEquals(0, confirmed)
        assertEquals(1, models); assertEquals(models, closedModels)
        assertTrue(logs.any { "barge_stop_rejected reason=unconfirmed_or_echo" in it })
    }
    @Test fun recognizedStopConfirmsOnlyAfterVerificationAndNativeRelease() = runBlocking {
        val audio = gate(text = "Stop.", speech = false, keywordAt = 10, keyword = "stop",
            onConfirmation = { natural -> assertFalse(natural); assertEquals(models, closedModels) }).chunks().toList()
        assertEquals(1, confirmed); assertEquals(1, models)
        assertTrue(audio.isNotEmpty())
        assertTrue(logs.any { "verification=asr_non_echo" in it })
    }
    @Test fun assistantSayingStopCannotConfirmItsOwnKeyword() = runBlocking {
        assertTrue(gate(text = "Stop.", reference = "You can say stop at any time.",
            speech = false, keywordAt = 10, keyword = "stop").chunks().toList().isEmpty())
        assertEquals(0, confirmed)
    }
    @Test fun stopWithoutVerificationBudgetLeavesPlaybackRunning() = runBlocking {
        assertTrue(gate(budget = false, speech = false, keywordAt = 10, keyword = "stop").chunks().toList().isEmpty())
        assertEquals(0, models); assertEquals(0, confirmed)
        assertTrue(logs.any { "barge_stop_rejected reason=verification_budget" in it })
    }
    @Test fun temporaryDecodeOverrunAllowsLaterNaturalCandidate() = runBlocking {
        var first = true
        val audio = gate(chunks = 60, acceptAction = {
            if (first) { first = false; clock += 1700 }
        }).chunks().toList()
        assertTrue(audio.isNotEmpty()); assertEquals(1, confirmed)
        assertTrue(models >= 2); assertEquals(models, closedModels)
        assertTrue(logs.any { "reason=decode_budget retryable=true" in it })
        assertFalse(logs.any { "barge_natural_unavailable" in it })
    }
    @Test fun ordinaryCorrectionPreservesOnsetAndStreamsFollowingAudioOnce() = runBlocking {
        val delivered = gate().chunks().toList()
        assertEquals(1, confirmed); assertEquals(1, models); assertEquals(models, closedModels)
        assertEquals(30 * 3200, delivered.sumOf { it.size })
        // The retained prefix starts with the first observed speech chunk, not confirmation.
        assertEquals(1, delivered.first()[0].toInt())
        assertEquals(30, delivered.last()[0].toInt())
    }
    @Test fun echoAndBackchannelsNeverPauseOrConfirm() = runBlocking {
        for (text in listOf("Can you open settings", "mm hmm", "okay", "The sky is blue")) {
            assertTrue(gate(text = text, reference = "Can you open settings? The sky is blue").chunks().toList().isEmpty())
        }
        assertEquals(0, confirmed); assertEquals(models, closedModels)
    }
    @Test fun silenceDoesNotLoadRecognizer() = runBlocking {
        assertTrue(gate(speech = false).chunks().toList().isEmpty())
        assertEquals(0, models); assertEquals(30, keywordCalls)
    }
    @Test fun lowSupplyLeavesKeywordListenerWorkingWithoutAsr() = runBlocking {
        val delivered = gate(budget = false, keywordAt = 15).chunks().toList()
        assertEquals(0, models); assertEquals(1, confirmed)
        assertEquals(15, keywordCalls); assertEquals(16, delivered.first()[0].toInt())
    }
    @Test fun recognizerFailureFallsBackWithoutLosingKeywordControl() = runBlocking {
        val delivered = gate(failing = true, keywordAt = 20).chunks().toList()
        assertEquals(1, models); assertEquals(models, closedModels); assertEquals(1, confirmed)
        assertEquals(21, delivered.first()[0].toInt())
        assertTrue(logs.any { it.contains("fallback=keyword") })
    }
    @Test fun sustainedEchoHasBoundedProbeCountAndNoDuplicateAudio() = runBlocking {
        assertTrue(gate(text = "The sky is blue", chunks = 500).chunks().toList().isEmpty())
        assertEquals(0, confirmed); assertTrue(models <= 4); assertEquals(models, closedModels)
        assertEquals(500, keywordCalls)
        assertTrue(logs.any { it.contains("reply_probe_limit") })
    }
    @Test fun resultFromDelayedWorkerCannotInterruptFromOldAudio() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val delivered = gate(chunks = 15, dispatcher = dispatcher, beforeFrame = { index ->
            if (index == 10) {
                clock += 1500
                while (pending.isNotEmpty()) pending.removeFirst().run()
            }
        }).chunks().toList()
        assertTrue(delivered.isEmpty()); assertEquals(0, confirmed)
        assertTrue(logs.any { it.contains("queued_audio_too_old") })
    }
    @Test fun keywordCanStopPlaybackWhileNativeDecodeIsBlocked() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val delivered = gate(chunks = 15, keywordAt = 12, dispatcher = Dispatchers.Default,
                beforeFrame = { index ->
                    if (index == 10) assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }, acceptAction = {
                    entered.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }, onConfirmation = { natural ->
                    assertFalse(natural)
                    assertEquals(1L, release.count) // Capture reached the keyword without waiting for ASR.
                    assertEquals(0, closedModels)
                    release.countDown()
                }).chunks().toList()
            assertEquals(1, confirmed)
            assertEquals(models, closedModels)
            assertEquals(13, delivered.first()[0].toInt())
        } finally { release.countDown() }
    }
    @Test fun completedResultIsConsumedDuringSilenceWithoutAnotherCandidate() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        var voiced = true
        gate(chunks = 23, dispatcher = dispatcher, speechNow = { voiced }, beforeFrame = { index ->
            if (index == 10) voiced = false
            if (index == 19) while (pending.isNotEmpty()) pending.removeFirst().run()
            if (index == 20) assertTrue(logs.any { it.contains("queued_audio_too_old") })
        }).chunks().toList()
        assertEquals(0, confirmed)
        assertEquals(0, models) // Old queued work is rejected before model acquisition.
    }
    @Test fun temporaryPlaybackPressureRecoversForLaterSpeechInSameReply() = runBlocking {
        var budget = true
        var first = true
        val delivered = gate(chunks = 40, budgetNow = { budget }, acceptAction = {
            if (first) { first = false; budget = false }
        }, beforeFrame = { index -> if (index == 15) budget = true }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(2, models)
        assertEquals(models, closedModels)
        assertTrue(delivered.isNotEmpty())
        assertTrue(logs.any { it.contains("barge_probe_deferred") })
        assertFalse(logs.any { it.contains("barge_natural_unavailable") })
    }
    @Test fun delayedCredibleResultSettlesBeforeSubmittingMoreAudio() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val delivered = gate(chunks = 23, dispatcher = dispatcher, beforeFrame = { index ->
            if (index == 11) while (pending.isNotEmpty()) pending.removeFirst().run()
        }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(1, models)
        assertEquals(23 * 3200, delivered.sumOf { it.size })
    }
    @Test fun temporarySevenHundredMsBacklogRecoversInSameReply() = runBlocking {
        var backlog = 700L
        val delivered = gate(chunks = 50, backlogNow = { backlog },
            beforeFrame = { if (it == 10) backlog = 0L }).chunks().toList()
        assertEquals(1, confirmed)
        assertTrue(delivered.isNotEmpty())
        assertTrue(logs.any { it.contains("barge_natural_suspended") })
        assertTrue(logs.any { it.contains("barge_natural_recovered") })
        assertTrue(logs.any { it.contains("backlogRecoveries=1") })
    }
    @Test fun twelveHundredMsNativeResultCanSettleAfterSpeechEnds() = runBlocking {
        var first = true
        var voiced = true
        val delivered = gate(chunks = 25, speechNow = { voiced }, acceptAction = {
            if (first) { clock += 1200; first = false; voiced = false }
        }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(1, models)
        assertTrue(delivered.isNotEmpty())
        assertFalse(logs.any { it.contains("barge_probe_discarded") })
    }

}
