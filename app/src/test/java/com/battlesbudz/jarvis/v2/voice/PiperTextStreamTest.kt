package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PiperTextStreamTest {
    @Test fun optionalSmallerOpeningReturnsToLongPassagesWithoutLosingWords() {
        val sentence = "We can continue with this explanation for a little longer. "
        val text = sentence.repeat(20).trim()
        val stream = PiperTextStream(openingTargetChars = 160)
        val parts = mutableListOf<String>()
        text.chunked(4).forEach { stream.append(it); while (true) parts += stream.take() ?: break }
        while (true) parts += stream.take(final = true) ?: break
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.first().length in 160 until 320)
        assertTrue(parts[1].length in 320..640)
        assertTrue(parts.all { it.endsWith(".") && it.length <= 640 })
    }
    @Test fun shortRepliesStayTogetherUntilGemmaFinishes() {
        val text = "Good evening, sir. Your appointment begins in twenty minutes. There is time for tea."
        val stream = PiperTextStream()
        text.chunked(4).forEach { stream.append(it); assertNull(stream.take()) }
        assertEquals(text, stream.take(final = true))
        assertNull(stream.take(final = true))
    }

    @Test fun storyProducesLargerBoundedPassagesWithoutLosingWords() {
        val text = TtsBenchmarkSamples.all.getValue("story-v1")
        val parts = collect(text)
        assertEquals(2, parts.size)
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.all { it.length <= PiperTextStream.MAX_CHARS })
        assertTrue(parts.first().endsWith("."))
    }

    @Test fun fullReplyModeWaitsForEndButStillBoundsNativeWork() {
        val text = (1..80).joinToString(" ") { "Sentence number $it is ready." }
        val stream = PiperTextStream(waitForEnd = true)
        text.chunked(4).forEach { stream.append(it); assertNull(stream.take()) }
        val parts = mutableListOf<String>()
        while (true) parts += stream.take(final = true) ?: break
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.all { it.length <= PiperTextStream.MAX_CHARS })
    }

    @Test fun runOnTextAndUnbrokenTokensAreNotTruncated() {
        val words = (1..500).joinToString(" ") { "word$it" }
        assertEquals(words, collect(words).joinToString(" "))
        val token = "x".repeat(1800)
        val parts = collect(token)
        assertEquals(token, parts.joinToString(""))
        assertTrue(parts.all { it.length <= PiperTextStream.MAX_CHARS })
    }

    @Test fun quotedSentenceAndAbbreviationsKeepTheirBoundaries() {
        val prefix = "We can continue with this explanation for a little longer. ".repeat(5)
        val text = prefix + "Dr. Smith replied, ‘That is correct.’ Then we finished."
        assertEquals(text.trim(), collect(text).joinToString(" "))
        assertFalse(collect(text).any { it.endsWith("Dr.") })
    }

    private fun collect(text: String): List<String> {
        val stream = PiperTextStream()
        val parts = mutableListOf<String>()
        text.chunked(4).forEach { token ->
            stream.append(token)
            while (true) parts += stream.take() ?: break
        }
        while (true) parts += stream.take(final = true) ?: break
        return parts
    }
}
