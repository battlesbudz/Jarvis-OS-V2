package com.battlesbudz.jarvis.v2.voice

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkProvenanceTest {
    @Test fun hashesActualInstalledFilesAndMarksUnmeasuredParity() = runBlocking {
        val directory = Files.createTempDirectory("voice-provenance").toFile()
        try {
            NorthernPiperSpec.files.keys.forEach { directory.resolve(it).also { file -> file.parentFile?.mkdirs(); file.writeText("abc") } }
            val result = BenchmarkProvenance.collect(TtsEngine.PIPER_NORTHERN, directory)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result["sha256.${TtsEngine.PIPER_NORTHERN.modelFile}"])
            assertEquals("upstream_sherpa_vits", result["nativePath"])
            directory.resolve(TtsEngine.PIPER_NORTHERN.modelFile).writeText("changed")
            assertNotEquals(result["sha256.${TtsEngine.PIPER_NORTHERN.modelFile}"], BenchmarkProvenance.collect(TtsEngine.PIPER_NORTHERN, directory)["sha256.${TtsEngine.PIPER_NORTHERN.modelFile}"])
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingArtifactCannotProduceAnApparentlyCompleteManifest() = runBlocking {
        val directory = Files.createTempDirectory("voice-missing").toFile()
        try {
            try {
                BenchmarkProvenance.collect(TtsEngine.PIPER_NORTHERN, directory)
                fail("Missing artifact must fail the requested benchmark")
            } catch (_: java.io.FileNotFoundException) { }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledSuiteStopsHashing() = runBlocking {
        val directory = Files.createTempDirectory("voice-cancel").toFile()
        try {
            directory.resolve(NorthernPiperSpec.files.keys.first()).writeBytes(ByteArray(128 * 1024))
            val job = Job().also { it.cancel() }
            try {
                withContext(job) { BenchmarkProvenance.collect(TtsEngine.PIPER_NORTHERN, directory) }
                fail("Cancelled suite must stop")
            } catch (_: CancellationException) { }
        } finally { directory.deleteRecursively() }
    }
}
