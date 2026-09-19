package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ModelGuidanceTest {
    private val gib = 1_073_741_824L
    private val phone = PhoneProfile("Samsung Fold6", "SM-F956U", "SM8650", 12*gib, 8*gib, 50*gib, true)
    @Test fun fold6GuidanceUsesTestExperienceWithoutPromisingSpeed() {
        assertEquals("Recommended for voice", ModelGuidance.assess(ModelCatalog.gemma4E2b, phone).label)
        assertEquals("Slower on Fold6", ModelGuidance.assess(ModelCatalog.gemma4E4b, phone).label)
    }
    @Test fun freeRamSnapshotDoesNotDisqualifyWorkingModels() {
        val occupied = phone.copy(availableRamBytes = 1)
        assertTrue(ModelGuidance.assess(ModelCatalog.gemma4E2b, occupied).recommended)
        val qwen = ModelCatalog.find("Qwen3-1.7B")!!
        assertEquals(ModelGuidance.assess(qwen, phone), ModelGuidance.assess(qwen, occupied))
    }
    @Test fun downloadStorageIsSeparateFromSuitability() {
        val full = phone.copy(freeStorageBytes = 1)
        val fit = ModelGuidance.assess(ModelCatalog.gemma4E2b, full)
        assertTrue(fit.recommended)
        assertNotNull(fit.storageNotice)
        assertNull(ModelGuidance.assess(ModelCatalog.gemma4E2b, full, installed = true).storageNotice)
        assertNull(ModelGuidance.assess(ModelCatalog.gemma4E2b, full.copy(freeStorageBytes = -1)).storageNotice)
    }
    @Test fun installedPassedTestOverridesLargeModelEstimate() {
        val qwen = ModelCatalog.find("Qwen3-8B")!!
        val small = phone.copy(model = "Other", totalRamBytes = 6*gib, availableRamBytes = gib)
        assertEquals("Large · test first", ModelGuidance.assess(qwen, small).label)
        assertEquals("Tested on your phone", ModelGuidance.assess(qwen, small, installed = true, testPassed = true).label)
        assertEquals("Large · test first", ModelGuidance.assess(qwen, small, installed = false, testPassed = true).label)
        assertEquals("Large · test first", ModelGuidance.assess(qwen, small, installed = true, testPassed = false).label)
    }
    @Test fun testDoesNotClaimSpeedOrBypassArchitectureRequirement() {
        val fit = ModelGuidance.assess(ModelCatalog.gemma4E4b, phone, installed = true, testPassed = true)
        assertEquals("Slower on Fold6", fit.label)
        assertEquals("Unsupported phone", ModelGuidance.assess(ModelCatalog.gemma4E2b,
            phone.copy(arm64 = false), installed = true, testPassed = true).label)
    }
    @Test fun unavailableMemoryUsesTestGuidanceUnlessAlreadyTested() {
        val unknown = phone.copy(model = "Other", totalRamBytes = 0)
        assertEquals("Try the model test", ModelGuidance.assess(ModelCatalog.gemma4E2b, unknown).label)
        assertEquals("Tested on your phone", ModelGuidance.assess(ModelCatalog.gemma4E2b,
            unknown, installed = true, testPassed = true).label)
    }
    @Test fun catalogOffersMultipleProvidersWithPinnedPortableArtifacts() {
        assertTrue(ModelCatalog.all.map { it.provider }.toSet().size >= 10)
        assertTrue(ModelCatalog.all.size >= 70)
        assertNull(ModelCatalog.find("1Bit-Bonsai-1.7B-PoC"))
        assertNull(ModelCatalog.find("gemma-4-12B-it-litert-lm"))
        assertFalse(ModelCatalog.find("Nemotron-3-Nano-4B")!!.recommendedGpu)
        assertTrue(ModelCatalog.find("LFM2.5-1.2B-Instruct")!!.downloadUrl!!.contains("int4_gpu"))
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
    }
}
