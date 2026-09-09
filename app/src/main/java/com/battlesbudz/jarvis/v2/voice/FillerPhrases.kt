package com.battlesbudz.jarvis.v2.voice

/** Neutral cues make no lookup, progress or success claims. */
internal object FillerPhrases {
    // Ask for a lingering hum; this text also invalidates the old clipped filler cache.
    const val INITIAL = "Ummm..."
    const val FOLLOWUP = "One second."
}
