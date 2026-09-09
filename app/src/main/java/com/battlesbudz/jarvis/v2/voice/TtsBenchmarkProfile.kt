package com.battlesbudz.jarvis.v2.voice

/** Reproducible speech settings; live calls use them only after the user applies a profile. */
data class TtsBenchmarkProfile(
    val threads: Int = 4,
    val openingChars: Int? = 40,
    val playbackSpeed: Float = 1f,
    val nativeStreaming: Boolean = false,
    val resetDecoder: Boolean = true,
    val leadingPeriod: Boolean = true,
    val bufferMs: Int = 200
) {
    init {
        require(nativeStreaming || (resetDecoder && leadingPeriod && bufferMs == 200))
        require(bufferMs in listOf(0, 200, 400))
        require(!nativeStreaming || openingChars == null)
        require(threads in listOf(2, 4))
        require(openingChars == null || openingChars in listOf(40, 60, 90))
        require(playbackSpeed == 1f || playbackSpeed == 0.9f)
    }
    val fullText: Boolean get() = openingChars == null && !nativeStreaming
    val legacyId: String get() = "threads-$threads-${if (nativeStreaming) "native-stream" else openingChars?.let { "opening-$it" } ?: "full-text"}-speed-${if (playbackSpeed == 1f) "1.0" else "0.9"}"
    val id: String get() = legacyId + if (nativeStreaming) "-reset-$resetDecoder-period-$leadingPeriod-buffer-$bufferMs" else ""
    val label: String get() = "$threads threads · ${if (nativeStreaming) "native audio stream" else openingChars?.let { "$it characters" } ?: "full text"} · ${if (playbackSpeed == 1f) "1.0" else "0.9"}×" + if (nativeStreaming) " · $stabilityLabel" else ""

    val stabilityLabel: String get() = "${if (resetDecoder) "Fresh decoder per sentence" else "Continuous decoder"} · period ${if (leadingPeriod) "on" else "off"} · ${bufferMs}ms cushion"

    companion object {
        val all: List<TtsBenchmarkProfile> = listOf(2, 4).flatMap { threads ->
            listOf<Int?>(40, 60, 90, null).flatMap { opening ->
                listOf(1f, 0.9f).map { speed -> TtsBenchmarkProfile(threads, opening, speed) }
            }
        }
        val nativeProfiles = listOf(2, 4).flatMap { threads ->
            listOf(1f, 0.9f).map { speed -> TtsBenchmarkProfile(threads, null, speed, nativeStreaming = true) }
        }
        val selectableProfiles: List<TtsBenchmarkProfile> get() = all + nativeProfiles.flatMap { base ->
            listOf(true, false).flatMap { reset -> listOf(true, false).flatMap { period ->
                listOf(0, 200, 400).map { buffer -> base.copy(resetDecoder = reset, leadingPeriod = period, bufferMs = buffer) }
            } }
        }
        val comparisonEngines: List<TtsEngine> = TtsEngine.entries.toList()
        val comparisonRunCount: Int get() = (all.size * comparisonEngines.size + nativeProfiles.size) * TtsBenchmarkSamples.all.size * 2
        val historyLimit: Int get() = maxOf(576, comparisonRunCount * 2)
    }
}

data class TtsBenchmarkRun(
    val suiteId: String,
    val profile: TtsBenchmarkProfile,
    val pass: Int,
    val text: String,
    val thermalStart: Int,
    val thermalEnd: Int,
    val audioFile: String? = null
)
