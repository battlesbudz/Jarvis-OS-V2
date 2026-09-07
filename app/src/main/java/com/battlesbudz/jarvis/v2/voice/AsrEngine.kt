package com.battlesbudz.jarvis.v2.voice

enum class AsrEngine(val id: String, val label: String, val modelVersion: String) {
    ZIPFORMER("zipformer", "Zipformer (current)", "en-20m-2023-02-17-int8 / sherpa-1.13.7"),
    MOONSHINE("moonshine_small", "Moonshine Small Streaming", "en-small-quantized_26_08_21 / moonshine-0.1.5");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: ZIPFORMER
    }
}
