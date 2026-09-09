package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class TtsBenchmarkProfileTest {
    @Test fun everySpeedOpeningAndThreadCombinationHasADistinctProfile() {
        val profiles = TtsBenchmarkProfile.all
        assertEquals(16, profiles.map { it.id }.toSet().size)
        for (threads in listOf(2, 4)) for (opening in listOf<Int?>(40, 60, 90, null)) {
            assertEquals(setOf(0.9f, 1f), profiles.filter {
                it.threads == threads && it.openingChars == opening
            }.map { it.playbackSpeed }.toSet())
        }
        assertEquals(listOf(TtsEngine.KOKORO, TtsEngine.PIPER_MIRO), TtsBenchmarkProfile.comparisonEngines)
    }

    @Test fun fullTextWaitsForEndAndKeepsAllSentencesInOneSynthesisRequest() {
        for (text in TtsBenchmarkSamples.all.values) {
            val chunker = SpeechChunker(fullText = true)
            for (token in text.chunked(4)) {
                chunker.append(token)
                assertNull(chunker.take())
            }
            assertEquals(text, chunker.take(final = true))
            assertNull(chunker.take(final = true))
        }
    }

    @Test fun sixtyCharacterOpeningReleasesBetweenFortyAndNinetyWithoutLosingText() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val openingLengths = listOf(40, 60, 90).map { size ->
            val chunker = SpeechChunker(size)
            val phrases = mutableListOf<String>()
            text.chunked(4).forEach { token ->
                chunker.append(token)
                while (true) phrases += chunker.take() ?: break
            }
            while (true) phrases += chunker.take(final = true) ?: break
            assertEquals(text, phrases.joinToString(" "))
            phrases.first().length
        }
        assertTrue(openingLengths[0] < openingLengths[1])
        assertTrue(openingLengths[1] < openingLengths[2])
    }
}
