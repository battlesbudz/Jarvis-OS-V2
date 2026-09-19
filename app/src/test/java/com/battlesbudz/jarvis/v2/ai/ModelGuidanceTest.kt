package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ModelGuidanceTest {
    private val gib = 1_073_741_824L
    private val phone = PhoneProfile("Samsung Fold6", "SM-F956U", "SM8650", 12*gib, 8*gib, 50*gib, true)

    @Test fun fold6CanHaveRoomForHeavyModelsWithoutClaimingTheyAreFast() {
        val e2b = ModelGuidance.assess(ModelCatalog.gemma4E2b, phone)
        val e4b = ModelGuidance.assess(ModelCatalog.gemma4E4b, phone)
        val qwen8 = ModelGuidance.assess(ModelCatalog.find("Qwen3-8B")!!, phone)
        assertEquals("Likely memory headroom", e2b.label)
        assertEquals(e2b.label, e4b.label)
        assertEquals("Light workload", e2b.workload)
        assertEquals("Moderate workload", e4b.workload)
        assertTrue(e4b.deviceExperience!!.contains("runs"))
        assertEquals("Heavy workload", qwen8.workload)
        assertTrue(qwen8.memoryRisk <= 1)
        assertTrue(ModelCatalog.all.count { ModelGuidance.assess(it, phone).memoryRisk <= 1 } >= 85)
    }

    @Test fun transientFreeRamStorageAndInstallationCannotChangeSuitabilityOrFamilyOrder() {
        ModelCatalog.all.forEach { spec ->
            assertEquals(spec.id, ModelGuidance.assess(spec, phone),
                ModelGuidance.assess(spec, phone.copy(availableRamBytes = 1, freeStorageBytes = 1)))
            // Installed status is accepted only by storage advice, never by suitability or sorting.
            ModelGuidance.storageNotice(spec, phone, installed = true)
            ModelGuidance.storageNotice(spec, phone, installed = false)
        }
        assertEquals(ModelGuide.families(), ModelGuide.families(ModelCatalog.all.reversed()))
    }

    @Test fun downloadStorageRemainsSeparateFromMemory() {
        val full = phone.copy(freeStorageBytes = 1)
        assertNotNull(ModelGuidance.storageNotice(ModelCatalog.gemma4E2b, full, installed = false))
        assertNull(ModelGuidance.storageNotice(ModelCatalog.gemma4E2b, full, installed = true))
        assertNull(ModelGuidance.storageNotice(ModelCatalog.gemma4E2b, full.copy(freeStorageBytes = -1), false))
    }

    @Test fun genuinelyLargeWeightsWarnButStayInTheCatalog() {
        val qwen14 = ModelCatalog.find("Qwen3-14B")!!
        assertTrue(ModelGuidance.assess(qwen14, phone).memoryRisk >= 2)
        val moe = ModelCatalog.find("gemma-4-26B-A4B-it-litert-lm")!!
        assertEquals("Bundle exceeds total RAM", ModelGuidance.assess(moe, phone).label)
        assertEquals(4.0, ModelGuide.parametersB(moe)!!, .01)
        assertTrue(ModelGuide.families()["Gemma"]!!.contains(moe))
        assertEquals("Less memory headroom", ModelGuidance.assess(ModelCatalog.find("Qwen3-8B")!!,
            phone.copy(totalRamBytes = 8*gib)).label)
        assertTrue(ModelGuidance.assess(ModelCatalog.find("Qwen3-8B")!!, phone.copy(totalRamBytes = 6*gib)).memoryRisk >= 2)
    }

    @Test fun memoryBoundariesAreExplicitAndUnknownInputsDoNotPretendToFit() {
        val spec = ModelCatalog.gemma4E2b
        listOf(.39 to 0, .40 to 1, .64 to 1, .65 to 2, .84 to 2, .85 to 3).forEach { (ratio, expected) ->
            assertEquals(expected, ModelGuidance.assess(spec.copy(downloadBytes = (ratio * 10000).toLong()),
                phone.copy(totalRamBytes = 10000)).memoryRisk)
        }
        assertEquals("Memory needs unknown", ModelGuidance.assess(spec.copy(downloadBytes = null), phone).label)
        assertEquals("Memory needs unknown", ModelGuidance.assess(spec, phone.copy(totalRamBytes = 0)).label)
        assertEquals("Unsupported architecture", ModelGuidance.assess(spec, phone.copy(arm64 = false)).label)
    }

    @Test fun compressionAndThinkingDoNotMasqueradeAsLowCompute() {
        val bonsai = ModelCatalog.find("Ternary-Bonsai-8B")!!
        assertEquals("Heavy workload", ModelGuidance.assess(bonsai, phone).workload)
        assertEquals("Likely memory headroom", ModelGuidance.assess(bonsai, phone).label)
        val thinking = ModelCatalog.find("LFM2.5-1.2B-Thinking")!!
        assertTrue(ModelGuidance.assess(thinking, phone).workloadExplanation.contains("long pause"))
        assertTrue(ModelGuidance.assess(ModelCatalog.find("Qwen3.5-2B")!!, phone).workloadExplanation.contains("CPU"))
    }

    @Test fun familiesCoverEveryModelOnceAndSortByArtifactSize() {
        val families = ModelGuide.families()
        assertEquals(ModelCatalog.all.size, families.values.flatten().size)
        assertEquals(ModelCatalog.all.toSet(), families.values.flatten().toSet())
        families.values.forEach { specs ->
            assertEquals(specs.map { it.downloadBytes }, specs.map { it.downloadBytes }.sortedBy { it ?: Long.MAX_VALUE })
        }
        assertEquals("Gemma", ModelGuide.family(ModelCatalog.find("codegemma-7b-it-int4-litertlm")!!))
        assertEquals("DeepSeek", ModelGuide.family(ModelCatalog.find("DeepSeek-R1-Distill-Qwen-1.5B")!!))
        assertEquals("Mistral", ModelGuide.family(ModelCatalog.find("Ministral-3-3B-Instruct-2512")!!))
        assertEquals("Phi", ModelGuide.family(ModelCatalog.find("Phi-4-mini-instruct")!!))
    }

    @Test fun everyCardHasVisiblePurposeAndSpecialistsHaveAccurateLimitations() {
        ModelCatalog.all.forEach {
            val purpose = ModelGuide.purpose(it)
            assertTrue(it.id, purpose.tags.isNotEmpty() && purpose.description.isNotBlank())
        }
        assertTrue(ModelGuide.purpose(ModelCatalog.find("Qwen2.5-Coder-1.5B-Instruct")!!).tags.contains("Coding"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("Llama-3.2-1B")!!).caveat.contains("base models"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("FastContext-1.0-4B-SFT")!!).caveat.contains("not connected"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("FastVLM-0.5B")!!).caveat.contains("not available"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("Hy-MT2-1.8B")!!).tags.contains("Translation"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("MedGemma-1.5-4B-IT")!!).tags.contains("Medical research"))
    }

    @Test fun searchingFindsPurposesAcrossFamiliesWithoutHidingHeavyModels() {
        val coding = ModelGuide.families(query = "coding")
        assertTrue(coding.keys.containsAll(listOf("Gemma", "Qwen")))
        assertTrue(ModelGuide.families(query = "26B")["Gemma"]!!.any { it.id.contains("26B") })
        assertTrue(ModelGuide.families(query = "no_such_model").isEmpty())
        assertEquals(ModelCatalog.gemma4E2b, ModelGuide.startingPoint(ModelGuide.families()["Gemma"]!!, phone))
        assertEquals("Qwen3-1.7B", ModelGuide.startingPoint(ModelGuide.families()["Qwen"]!!, phone)!!.id)
        assertNull(ModelGuide.startingPoint(ModelGuide.families()["Llama"]!!, phone))
        assertNull(ModelGuide.startingPoint(ModelGuide.families()["Gemma"]!!, phone.copy(arm64 = false)))
    }
}
