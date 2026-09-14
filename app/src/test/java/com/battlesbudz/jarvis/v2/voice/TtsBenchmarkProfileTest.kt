package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class TtsBenchmarkProfileTest {
    @org.junit.Test fun slowerProfileCanBePersistedWithoutChangingOldIds() {
        val slow = TtsBenchmarkProfile(4, null, 0.85f, nativeStreaming = true)
        org.junit.Assert.assertTrue(slow.id.contains("speed-0.85"))
        org.junit.Assert.assertEquals(slow, TtsBenchmarkProfile.selectableProfiles.single { it.id == slow.id })
        org.junit.Assert.assertTrue(TtsBenchmarkProfile(playbackSpeed = 0.9f).id.endsWith("speed-0.9"))
    }

    @Test fun everySpeedOpeningAndThreadCombinationHasADistinctProfile() {
        val profiles = TtsBenchmarkProfile.all
        assertEquals(16, profiles.map { it.id }.toSet().size)
        for (threads in listOf(2, 4)) for (opening in listOf<Int?>(40, 60, 90, null)) {
            assertEquals(setOf(0.9f, 1f), profiles.filter {
                it.threads == threads && it.openingChars == opening
            }.map { it.playbackSpeed }.toSet())
        }
        assertEquals(TtsEngine.entries.toList(), TtsBenchmarkProfile.comparisonEngines)
    }

    @Test fun nativeAudioProfilesAreDistinctFromTheBufferedBaseline() {
        val native = TtsBenchmarkProfile.nativeProfiles
        assertEquals(4, native.size)
        assertTrue(native.all { it.nativeStreaming && !it.fullText && it.openingChars == null })
        assertEquals(20, (TtsBenchmarkProfile.all + native).map { it.id }.toSet().size)
        assertEquals(216, TtsBenchmarkProfile.comparisonRunCount)
        assertTrue(TtsBenchmarkProfile.historyLimit >= 576)
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
