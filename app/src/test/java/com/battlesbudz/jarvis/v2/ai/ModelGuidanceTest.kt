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
    @Test fun lowMemoryAndLowStorageOverrideRecommendations() {
        assertFalse(ModelGuidance.assess(ModelCatalog.gemma4E2b, phone.copy(availableRamBytes=gib)).recommended)
        assertEquals("Free storage needed", ModelGuidance.assess(ModelCatalog.gemma4E2b, phone.copy(freeStorageBytes=1)).label)
        assertNotEquals("Free storage needed", ModelGuidance.assess(ModelCatalog.gemma4E2b, phone.copy(freeStorageBytes=1), installed=true).label)
        assertEquals("Likely too large", ModelGuidance.assess(ModelCatalog.find("Qwen3-8B")!!, phone.copy(totalRamBytes=6*gib)).label)
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
