package com.battlesbudz.jarvis.v2.voice

/** Explicit, reproducible test settings; never applied to live calls. */
data class TtsBenchmarkProfile(
    val threads: Int = 4,
    val openingChars: Int? = 40,
    val playbackSpeed: Float = 1f
) {
    init {
        require(threads in listOf(2, 4))
        require(openingChars == null || openingChars in listOf(40, 60, 90))
        require(playbackSpeed == 1f || playbackSpeed == 0.9f)
    }
    val fullText: Boolean get() = openingChars == null
    val id: String get() = "threads-$threads-${openingChars?.let { "opening-$it" } ?: "full-text"}-speed-${if (playbackSpeed == 1f) "1.0" else "0.9"}"
    val label: String get() = "$threads threads · ${openingChars?.let { "$it characters" } ?: "full text"} · ${if (playbackSpeed == 1f) "1.0" else "0.9"}×"

    companion object {
        val all: List<TtsBenchmarkProfile> = listOf(2, 4).flatMap { threads ->
            listOf<Int?>(40, 60, 90, null).flatMap { opening ->
                listOf(1f, 0.9f).map { speed -> TtsBenchmarkProfile(threads, opening, speed) }
            }
        }
        val comparisonEngines: List<TtsEngine> = TtsEngine.entries.toList()
        val comparisonRunCount: Int get() = all.size * TtsBenchmarkSamples.all.size * comparisonEngines.size * 2
        val historyLimit: Int get() = comparisonRunCount * 2
    }
}

data class TtsBenchmarkRun(
    val suiteId: String,
    val profile: TtsBenchmarkProfile,
    val pass: Int,
    val text: String,
    val thermalStart: Int,
    val thermalEnd: Int
)
