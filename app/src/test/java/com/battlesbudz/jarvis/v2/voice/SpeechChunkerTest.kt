package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechChunkerTest {
    @Test fun tokenSplitsAndDecimalsPreserveText() {
        val chunks = SpeechChunker()
        chunks.append("It costs 3.")
        assertNull(chunks.take())
        chunks.append("50 dollars. Next")
        assertEquals("It costs 3.50 dollars.", chunks.take())
        assertNull(chunks.take())
        assertEquals("Next", chunks.take(final = true))
        assertNull(chunks.take(final = true))
    }

    @Test fun shortReplyFlushesWithoutWaitingForAnotherSentence() {
        val chunks = SpeechChunker()
        chunks.append("Hello, sir.")
        assertEquals("Hello, sir.", chunks.take(final = true))
    }

    @Test fun longTokenStreamLosesNoWordsAndStartsWithShorterChunk() {
        val chunks = SpeechChunker()
        val text = (1..150).joinToString(" ") { "word$it" }
        val result = mutableListOf<String>()
        for (character in text) {
            chunks.append(character.toString())
            while (true) result += chunks.take() ?: break
        }
        while (true) result += chunks.take(final = true) ?: break
        assertEquals(text, result.joinToString(" "))
        assertTrue(result.first().length < result[1].length)
        assertTrue(result.all { it.length < 200 })
    }

    @Test fun slowSynthesisReducesFollowingChunkSize() {
        fun next(rtf: Double): String {
            val chunks = SpeechChunker()
            chunks.append("Hello. ")
            chunks.take()
            chunks.observe(rtf)
            chunks.append((1..60).joinToString(" ") { "word" })
            return requireNotNull(chunks.take())
        }
        assertTrue(next(1.7).length < next(0.7).length)
    }

    @Test fun firstClauseCanStartBeforeLongSentenceFinishes() {
        val chunks = SpeechChunker()
        chunks.append("Beyond the quiet harbor and its old lighthouse, the pirate captain watched the distant horizon")
        assertEquals("Beyond the quiet harbor and its old lighthouse,", chunks.take())
    }
}
