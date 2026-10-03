package com.battlesbudz.jarvis.v2.memory

/** Content-free erase barrier blocks all older retained sources, including paraphrased derivatives. */
object MemorySuppression {
    fun barrier(snapshot: MemorySnapshot): Long = snapshot.tombstones.maxOfOrNull { it.erasedAtMs } ?: 0
    fun suppressed(source: SourceEpisode, snapshot: MemorySnapshot): Boolean = source.capturedAtMs <= barrier(snapshot)
}
