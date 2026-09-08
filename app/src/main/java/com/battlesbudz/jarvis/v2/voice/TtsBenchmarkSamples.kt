package com.battlesbudz.jarvis.v2.voice

/** Versioned fixed inputs: all voices get identical text, chunk boundaries and normal playback. */
object TtsBenchmarkSamples {
    val all = linkedMapOf(
        "short-v1" to "The monkey in the story was named Kiko.",
        "paragraph-v1" to "Good evening, sir. Your next appointment begins in twenty minutes. There is time for a cup of tea, provided we do not attempt to invent a new kettle first. I will keep the details ready while you finish what you are doing.",
        "story-v1" to "Kiko stood on the deck as the moon rose above the island. The little monkey had found a map inside an old brass compass, and tonight his crew would follow it. Beyond the reef, a blue light flickered beneath the waves. Kiko lowered a lantern and discovered the roof of a sunken library. Its windows were still glowing. He smiled, tied a rope around his waist, and handed the other end to his first mate. Gold could wait. Somewhere below them was a story that no pirate had ever heard, and Kiko intended to bring it home."
    )
}
