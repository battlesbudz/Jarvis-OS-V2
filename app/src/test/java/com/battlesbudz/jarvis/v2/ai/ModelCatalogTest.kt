package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun existingInstallationsKeepE2b() {
        assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve(null))
        assertEquals("gemma-4-E2B-it.litertlm", ModelCatalog.resolve(null).fileName)
    }

    @Test fun savedE4bSelectionIsRestored() {
        assertEquals(ModelCatalog.gemma4E4b, ModelCatalog.resolve("Gemma-4-E4B-it"))
        assertNotEquals(ModelCatalog.gemma4E2b.fileName, ModelCatalog.gemma4E4b.fileName)
    }

    @Test fun removedPreferenceFallsBackButUnknownWorkRequestIsRejected() {
        assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve("removed-model"))
        assertNull(ModelCatalog.find("removed-model"))
    }

    @Test fun downloadsHaveSeparateFilesAndPinnedChecksums() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.toSet().size)
        ModelCatalog.all.forEach { spec ->
            assertTrue(spec.expectedSha256!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue(spec.downloadUrl!!.startsWith("https://huggingface.co/"))
            assertFalse(spec.downloadUrl.contains("/resolve/main/"))
            assertTrue(spec.downloadUrl.substringBefore('?').endsWith(".litertlm"))
        }
    }

    @Test fun qwenSelectionsDoNotEnableGemmaOnlyPaths() {
        assertEquals(13, ModelCatalog.qwen.size)
        ModelCatalog.qwen.forEach { spec ->
            assertEquals(spec, ModelCatalog.resolve(spec.id))
            assertFalse(spec.supportsAudio)
            assertFalse(spec.supportsTools)
            assertFalse(spec.incrementalGemmaInput)
            assertTrue(spec.downloadBytes!! > 0)
            assertTrue(spec.contextTokens!! in 2048..4096)
        }
        assertTrue(ModelCatalog.gemma4E2b.incrementalGemmaInput)
        assertTrue(ModelCatalog.gemma4E4b.supportsAudio)
        assertEquals(listOf("Qwen2-VL-2B"), ModelCatalog.qwen.filter { it.supportsVision }.map { it.id })
        assertFalse(ModelCatalog.find("Qwen3.5-4B")!!.recommendedGpu)
        assertNotNull(ModelCatalog.find("Qwen2.5-Coder-1.5B-Instruct"))
        assertNotNull(ModelCatalog.find("Qwen2.5-Coder-3B-Instruct"))
    }
}
