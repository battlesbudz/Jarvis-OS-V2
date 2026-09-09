package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class PocketTextStreamTest {
    @Test fun readyParagraphReleasesOneSentencePerSubmissionWithoutLosingText() {
        val stream = PocketTextStream()
        stream.append("The first sentence. The second sentence. A partial")
        assertEquals("The first sentence.", stream.take())
        assertEquals("The second sentence.", stream.take())
        stream.append(" tail")
        assertNull(stream.take())
        assertEquals("A partial tail", stream.take(final = true))
        assertNull(stream.take(final = true))
    }
    @Test fun longUnfinishedSentenceNeverReleasesAtACharacterBudget() {
        val stream = PocketTextStream()
        val text = (1..120).joinToString(" ") { "word$it" }
        text.chunked(3).forEach { stream.append(it); assertNull(stream.take()) }
        assertEquals(text, stream.take(final = true))
        assertNull(stream.take(final = true))
    }
    @Test fun naturalBoundariesPreserveDecimalsAbbreviationsAndTokenSplitWords() {
        val text = "Dr. Paul paid 3.25 dollars. He said, ‘Hello!’ Then left."
        val stream = PocketTextStream(); val parts = mutableListOf<String>()
        text.chunked(2).forEach { stream.append(it); while (true) parts += stream.take() ?: break }
        while (true) parts += stream.take(final = true) ?: break
        assertEquals(listOf("Dr. Paul paid 3.25 dollars.", "He said, ‘Hello!’", "Then left."), parts)
        assertEquals(text, parts.joinToString(" "))
    }
}
