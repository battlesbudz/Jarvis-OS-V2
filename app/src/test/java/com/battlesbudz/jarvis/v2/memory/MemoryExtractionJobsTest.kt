package com.battlesbudz.jarvis.v2.memory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class MemoryExtractionJobsTest {
    private class Jobs(private val attempt: Int = 1) : MemoryExtractionJobs {
        var claims=0;var deferred=0;var failures=0;var commits=0
        val deferredAttempts=mutableListOf<Pair<Int,Boolean>>()
        override fun claimExtraction():MemoryExtractionJob? {claims++;return if(claims==1)MemoryExtractionJob("event","lease",extractionSource(),attempt) else null}
        override fun finishExtraction(job:MemoryExtractionJob,facts:List<ExtractedMemory>):Boolean {commits++;return true}
        override fun deferExtraction(job:MemoryExtractionJob,failed:Boolean):Boolean {deferred++;deferredAttempts+=job.attempt to failed;if(failed)failures++;return deferred==1}
        override fun extractionJobStates()=emptyMap<String,String>()
    }
    @Test fun foregroundCancellationDefersWithoutCommittingAndJoinsExactChild()=runBlocking {
        val jobs=Jobs();val entered=CompletableDeferred<Unit>()
        val worker=MemoryExtractionWorker(jobs,LocalMemoryExtractor{entered.complete(Unit);awaitCancellation()},{true})
        val child=launch{worker.runBatch()};entered.await();child.cancelAndJoin()
        assertEquals(0,jobs.commits);assertEquals(1,jobs.deferred);assertEquals(0,jobs.failures)
    }
    @Test fun modelFailureIsBoundedAndUnavailableForegroundDoesNotClaim()=runBlocking {
        val jobs=Jobs()
        MemoryExtractionWorker(jobs,LocalMemoryExtractor{error("model unavailable")},{true}).runBatch()
        assertEquals(1,jobs.claims);assertEquals(0,jobs.commits);assertEquals(1,jobs.failures)
        val busy=Jobs();MemoryExtractionWorker(busy,LocalMemoryExtractor{emptyList()},{false}).runBatch()
        assertEquals(0,busy.claims)
    }
    @Test fun fifthClaimCancellationAndFailureKeepDistinctDeferIntents()=runBlocking {
        val cancel=Jobs(5);val entered=CompletableDeferred<Unit>()
        val child=launch { MemoryExtractionWorker(cancel,LocalMemoryExtractor{entered.complete(Unit);awaitCancellation()},{true}).runBatch() }
        entered.await();child.cancelAndJoin()
        assertEquals(listOf(5 to false),cancel.deferredAttempts)
        assertEquals(0,cancel.commits)
        val failed=Jobs(5)
        MemoryExtractionWorker(failed,LocalMemoryExtractor{error("model failure")},{true}).runBatch()
        assertEquals(listOf(5 to true,5 to false),failed.deferredAttempts)
        assertEquals(1,failed.failures);assertEquals(0,failed.commits)
    }
}
