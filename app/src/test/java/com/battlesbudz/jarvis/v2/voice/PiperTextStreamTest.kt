package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PiperTextStreamTest {
    private val earlySentence = "A civilization capable of that would need to harness energy on a stellar scale."

    @Test fun fasterOpeningReleasesCompletedSentenceAtDeadlineWithoutNewTokens() {
        var clock = 0L
        val stream = PiperTextStream(openingTargetChars = 160, nowMs = { clock })
        stream.append("$earlySentence The next explanation")
        assertNull(stream.take())
        assertEquals(750L, stream.openingWaitMs())
        clock = 749
        assertNull(stream.take())
        clock = 750
        assertEquals(earlySentence, stream.take())
        assertNull(stream.openingWaitMs())
        stream.append(" continues here.")
        clock = 5000
        assertNull(stream.take()) // Later passages retain the 320-character policy.
        assertEquals("The next explanation continues here.", stream.take(final = true))
    }

    @Test fun deadlineNeverReleasesAnIncompleteSentenceOrShortAcknowledgement() {
        var clock = 0L
        val stream = PiperTextStream(openingTargetChars = 160, nowMs = { clock })
        stream.append("Yes, sir. " + "This explanation is not finished yet ".repeat(3))
        clock = 5000
        assertNull(stream.openingWaitMs())
        assertNull(stream.take())
        stream.append("and now it is. ")
        assertNotNull(stream.take())
    }

    @Test fun deadlineDoesNotChangeDefaultOrFullReplyPolicies() {
        var clock = 0L
        val normal = PiperTextStream(nowMs = { clock })
        val full = PiperTextStream(waitForEnd = true, openingTargetChars = 160, nowMs = { clock })
        for (stream in listOf(normal, full)) stream.append("$earlySentence Another sentence is ready. ")
        clock = 10_000
        for (stream in listOf(normal, full)) {
            assertNull(stream.openingWaitMs()); assertNull(stream.take())
            assertEquals("$earlySentence Another sentence is ready.", stream.take(final = true))
        }
    }

    @Test fun waitingForOpeningDoesNotMistakeAnAbbreviationForASentence() {
        var clock = 0L
        val stream = PiperTextStream(openingTargetChars = 160, nowMs = { clock })
        stream.append("The physician who will explain all the details to you today is Dr. ")
        clock = 1000
        assertNull(stream.take()); assertNull(stream.openingWaitMs())
        stream.append("Smith. ")
        assertTrue(requireNotNull(stream.take()).endsWith("Dr. Smith."))
    }
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
