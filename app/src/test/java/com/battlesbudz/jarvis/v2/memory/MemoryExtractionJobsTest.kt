package com.battlesbudz.jarvis.v2.memory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class MemoryExtractionJobsTest {
    private class Jobs : MemoryExtractionJobs {
        var claims=0;var deferred=0;var failures=0;var commits=0
        override fun claimExtraction():MemoryExtractionJob? {claims++;return if(claims==1)MemoryExtractionJob("event","lease",extractionSource(),1) else null}
        override fun finishExtraction(job:MemoryExtractionJob,facts:List<ExtractedMemory>):Boolean {commits++;return true}
        override fun deferExtraction(job:MemoryExtractionJob,failed:Boolean):Boolean {deferred++;if(failed)failures++;return true}
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
}
