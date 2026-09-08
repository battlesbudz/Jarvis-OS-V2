package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Conservative text cues plus acoustic silence. This is not a semantic model. */
class AdaptiveTurnEnd {
    data class Decision(val silenceMs: Long, val cue: String)
    private var transcript = ""
    private var changedAtMs = 0L

    fun update(text: String, nowMs: Long) {
        val normalized = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (normalized != transcript) { transcript = normalized; changedAtMs = nowMs }
    }

    fun decision(nowMs: Long): Decision {
        val words = Regex("[\\p{L}\\p{N}']+").findAll(transcript).map { it.value }.toList()
        if (words.isEmpty()) return Decision(3000, "no_transcript")
        val last = words.last()
        if (last in unfinished || transcript.endsWith("...") || transcript.endsWith("…") ||
            Regex("(?:i mean|let me think|hold on|wait a second|you know|tell me|show me|give me)$")
                .containsMatchIn(words.joinToString(" "))) {
            return Decision(3000, "hesitation_or_unfinished")
        }
        // Punctuation is only a cue: ASR can add a question mark to an unfinished fragment.
        val question = words.size >= 4 && words.first() in questionStarts
        val shortReply = words.joinToString(" ") in replies
        val sentence = words.size >= 4 && transcript.lastOrNull() in listOf('.', '?', '!')
        val stable = (nowMs - changedAtMs).coerceAtLeast(0)
        return when {
            (question || shortReply || sentence) && stable >= 300 -> Decision(350, "complete_and_stable")
            question || shortReply || sentence -> Decision(1100, "transcript_settling")
            else -> Decision(1500, "uncertain")
        }
    }

    fun reset() { transcript = ""; changedAtMs = 0L }

    private companion object {
        val questionStarts = setOf("what", "where", "when", "why", "who", "whose", "which", "how",
            "is", "are", "was", "were", "can", "could", "would", "should", "will", "do", "does", "did")
        val replies = setOf("yes", "no", "okay", "ok", "sure", "thanks", "thank you", "yes please",
            "no thanks", "goodbye", "goodbye jarvis", "stop listening", "stop listening jarvis")
        val unfinished = setOf("um", "uh", "erm", "hmm", "and", "or", "but", "because", "if", "so",
            "although", "while", "to", "from", "with", "without", "about", "of", "for", "at", "in", "on",
            "into", "than", "the", "a", "an", "my", "your", "our", "their", "his", "her", "its",
            "is", "are", "was", "were", "be", "been", "being", "can", "could", "would", "should", "will",
            "do", "does", "did", "have", "has", "had", "what", "which", "how", "whether")
    }
}
