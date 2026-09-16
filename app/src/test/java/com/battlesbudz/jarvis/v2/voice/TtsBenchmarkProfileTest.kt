package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class TtsBenchmarkProfileTest {
    @Test fun comparisonIncludesOnlyPiperAndBothPassageOpenings() {
        assertEquals(listOf(TtsEngine.PIPER_NORTHERN), TtsBenchmarkProfile.comparisonEngines)
        assertEquals(24, TtsBenchmarkProfile.comparisonProfiles.map { it.id }.toSet().size)
        assertEquals(144, TtsBenchmarkProfile.comparisonRunCount)
        assertTrue(TtsBenchmarkProfile.comparisonProfiles.any { it.openingChars == 160 })
        assertTrue(TtsBenchmarkProfile.comparisonProfiles.any { it.openingChars == 320 })
    }

    @Test fun fasterPiperOpeningIsSelectableWithoutChangingExistingProfileIds() {
        val faster = TtsBenchmarkProfile(2, 160)
        assertTrue(faster.piperPassages)
        assertEquals(faster, TtsBenchmarkProfile.selectableProfiles.single { it.id == faster.id })
        assertEquals("threads-2-opening-320-speed-1.0", TtsBenchmarkProfile(2, 320).id)
    }
    @org.junit.Test fun slowerProfileCanBePersistedWithoutChangingOldIds() {
        val slow = TtsBenchmarkProfile(4, null, 0.85f)
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
