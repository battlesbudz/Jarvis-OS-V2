package com.battlesbudz.jarvis.v2.voice

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkProvenanceTest {
    @Test fun hashesActualInstalledFilesAndMarksUnmeasuredParity() = runBlocking {
        val directory = Files.createTempDirectory("voice-provenance").toFile()
        try {
            (PocketVoiceSpec.files.keys + PocketVoiceSpec.PAUL_FILE).forEach { directory.resolve(it).writeText("abc") }
            val result = BenchmarkProvenance.collect(TtsEngine.POCKET_PAUL, directory)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result["sha256.paul.wav"])
            assertEquals("not_assessed", result["upstreamParity"])
            directory.resolve("paul.wav").writeText("changed")
            assertNotEquals(result["sha256.paul.wav"], BenchmarkProvenance.collect(TtsEngine.POCKET_PAUL, directory)["sha256.paul.wav"])
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingArtifactCannotProduceAnApparentlyCompleteManifest() = runBlocking {
        val directory = Files.createTempDirectory("voice-missing").toFile()
        try {
            try {
                BenchmarkProvenance.collect(TtsEngine.POCKET_PAUL, directory)
                fail("Missing artifact must fail the requested benchmark")
            } catch (_: java.io.FileNotFoundException) { }
            assertEquals("not_collected", BenchmarkProvenance.collect(TtsEngine.KOKORO, directory)["artifactHashStatus"])
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledSuiteStopsHashing() = runBlocking {
        val directory = Files.createTempDirectory("voice-cancel").toFile()
        try {
            directory.resolve(PocketVoiceSpec.files.keys.first()).writeBytes(ByteArray(128 * 1024))
            val job = Job().also { it.cancel() }
            try {
                withContext(job) { BenchmarkProvenance.collect(TtsEngine.POCKET_PAUL, directory) }
                fail("Cancelled suite must stop")
            } catch (_: CancellationException) { }
        } finally { directory.deleteRecursively() }
    }
}
