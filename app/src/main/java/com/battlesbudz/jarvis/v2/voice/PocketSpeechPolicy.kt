package com.battlesbudz.jarvis.v2.voice

/** One repeatable synthesis policy for live speech, silent preparation and cached fillers. */
internal object PocketSpeechPolicy {
    const val VERSION = "sentence-seed42-v2"
    const val SEED = 42
    fun extra(filler: Boolean = false): Map<String, String> = mapOf(
        "temperature" to "0.7", "seed" to SEED.toString(), "chunk_size" to "15",
        "max_reference_audio_len" to "15",
        // Keep decimals/abbreviations within the app's bounded sentence, even if native
        // SplitByPunctuation splits at their dots before MergeShortSentences runs.
        "min_char_in_sentence" to SpeechChunker.SENTENCE_LIMIT.toString(),
        "max_char_in_sentence" to SpeechChunker.SENTENCE_LIMIT.toString()
    ) + if (filler) mapOf("max_frames" to "50") else emptyMap()
}
