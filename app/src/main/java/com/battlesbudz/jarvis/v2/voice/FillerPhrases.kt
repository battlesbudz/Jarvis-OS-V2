package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.atomic.AtomicInteger

/** No action/success claims in neutral fillers. Lookup wording requires an actual lookup. */
internal object FillerPhrases {
    private val sequence = AtomicInteger()
    private val neutral = listOf("Uh, one moment.", "Um, let me think.", "One moment.")
    fun nextNeutral(): String = neutral[Math.floorMod(sequence.getAndIncrement(), neutral.size)]
    const val CHECKING = "Checking that."
}
