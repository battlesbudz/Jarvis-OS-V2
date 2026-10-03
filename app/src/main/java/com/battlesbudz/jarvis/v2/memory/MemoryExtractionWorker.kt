package com.battlesbudz.jarvis.v2.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Bounded idle batch. Foreground owner cancels and joins this exact child before model use. */
class MemoryExtractionWorker(private val jobs: MemoryExtractionJobs, private val extractor: LocalMemoryExtractor,
    private val isIdle: () -> Boolean, private val onCommitted: () -> Unit = {}) {
    suspend fun runBatch() {
        repeat(4) {
            if (!isIdle()) return
            val job = jobs.claimExtraction() ?: return
            var committed = false
            val generation = jobs.extractionGeneration()
            try {
                val facts = withTimeout(60_000) { extractor.extract(job.source) }
                if (!isIdle()) return
                committed = jobs.finishExtraction(job, facts)
                if (committed) { if (generation != jobs.extractionGeneration()) onCommitted() }
                else { jobs.deferExtraction(job, failed = true); return }
            } catch (_: TimeoutCancellationException) {
                withContext(NonCancellable) { jobs.deferExtraction(job, failed = true) }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable) { jobs.deferExtraction(job, failed = true) }
                return // A malformed/model-failed attempt cannot spin indefinitely.
            } finally {
                if (!committed) withContext(NonCancellable) { jobs.deferExtraction(job) }
            }
        }
    }
}
