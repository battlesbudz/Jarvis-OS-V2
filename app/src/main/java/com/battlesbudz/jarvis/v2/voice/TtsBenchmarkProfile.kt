package com.battlesbudz.jarvis.v2.voice

/** Piper comparison profiles; existing Piper IDs remain stable across upgrades. */
data class TtsBenchmarkProfile(
    val threads: Int = 4,
    val openingChars: Int? = 40,
    val playbackSpeed: Float = 1f
) {
    init {
        require(threads in listOf(2, 4))
        require(openingChars == null || openingChars in listOf(40, 60, 90, 160, 320))
        require(playbackSpeed == 1f || playbackSpeed == 0.9f || playbackSpeed == 0.85f)
    }
    val piperPassages: Boolean get() = openingChars in listOf(160, 320)
    val fullText: Boolean get() = openingChars == null
    val id: String get() = "threads-$threads-${openingChars?.let { "opening-$it" } ?: "full-text"}-speed-${playbackSpeed}"
    val label: String get() = "$threads threads · ${openingChars?.let { "$it-character opening" } ?: "full text"} · ${playbackSpeed}×"
    companion object {
        val all = listOf(2, 4).flatMap { threads ->
            listOf<Int?>(40, 60, 90, null).flatMap { opening ->
                listOf(1f, 0.9f).map { speed -> TtsBenchmarkProfile(threads, opening, speed) }
            }
        }
        val piperProfiles = listOf(2, 4).flatMap { threads ->
            listOf(160, 320).flatMap { opening ->
                listOf(1f, 0.9f).map { speed -> TtsBenchmarkProfile(threads, opening, speed) }
            }
        }
        val comparisonProfiles = all + piperProfiles
        val selectableProfiles = (comparisonProfiles + comparisonProfiles.map { it.copy(playbackSpeed = 0.85f) }).distinctBy { it.id }
        val comparisonEngines = listOf(TtsEngine.PIPER_NORTHERN)
        val comparisonRunCount: Int get() = comparisonProfiles.size * TtsBenchmarkSamples.all.size * 2
        // Preserve previously saved full suites, including retired engine measurements.
        const val historyLimit = 624
    }
}

data class TtsBenchmarkRun(
    val suiteId: String,
    val profile: TtsBenchmarkProfile,
    val pass: Int,
    val text: String,
    val thermalStart: Int,
    val thermalEnd: Int,
    val audioFile: String? = null,
    val inputDelivery: String = "4 characters every 32 ms after model ready",
    val submissions: List<String>? = null,
    val provenance: Map<String, String> = emptyMap()
)
