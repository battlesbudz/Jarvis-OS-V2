package com.battlesbudz.jarvis.v2.memory

import androidx.annotation.Keep

@Keep
data class MemoryExtractionJob(val eventKey: String, val lease: String, val source: SourceEpisode, val attempt: Int)

/** Claims and commits are transactional; a lease is not renewed by process restart. */
@Keep
interface MemoryExtractionJobs {
    fun claimExtraction(): MemoryExtractionJob?
    fun finishExtraction(job: MemoryExtractionJob, facts: List<ExtractedMemory>): Boolean
    fun deferExtraction(job: MemoryExtractionJob, failed: Boolean = false): Boolean
    fun extractionJobStates(): Map<String, String>
    fun indexJobStates(): Map<Long, String> = emptyMap()
    fun extractionGeneration(): Long = -1
}
