package com.battlesbudz.jarvis.v2.ai

/** Resource estimates are fallback guidance, not benchmarks or driver compatibility tests. */
data class PhoneProfile(
    val name: String, val model: String, val chip: String,
    val totalRamBytes: Long, val availableRamBytes: Long, val freeStorageBytes: Long,
    val arm64: Boolean
)
data class ModelFit(
    val label: String,
    val explanation: String,
    val recommended: Boolean = false,
    val storageNotice: String? = null
)

object ModelGuidance {
    private const val GIB = 1_073_741_824L
    fun estimatedWorkingBytes(spec: LocalModelSpec): Long =
        ((spec.downloadBytes ?: 3 * GIB) * if (spec.recommendedGpu) 1.8 else 1.4).toLong() + GIB

    fun assess(
        spec: LocalModelSpec,
        phone: PhoneProfile,
        installed: Boolean = false,
        testPassed: Boolean = false
    ): ModelFit {
        if (!phone.arm64) return ModelFit("Unsupported phone", "This app needs a 64-bit ARM phone.")
        val fit = suitability(spec, phone, installed && testPassed)
        // Storage governs downloading, not how well an already installed model runs.
        val bytes = spec.downloadBytes
        return fit.copy(storageNotice = if (!installed && bytes != null && phone.freeStorageBytes >= 0 &&
            phone.freeStorageBytes < bytes + GIB)
            "Free storage before downloading. Leave extra room for model caches." else null)
    }

    private fun suitability(spec: LocalModelSpec, phone: PhoneProfile, tested: Boolean): ModelFit {
        // Known device experience and actual generation tests outrank size heuristics.
        if (spec.id == "Gemma-4-E2B-it" && phone.model.startsWith("SM-F956", true))
            return ModelFit("Recommended for voice", "E2B has worked well in Fold6 voice tests. Low free RAM alone does not mean it won't fit.", true)
        if (spec.id == "Gemma-4-E4B-it" && phone.model.startsWith("SM-F956", true))
            return ModelFit("Slower on Fold6", "E4B has run slowly in Fold6 tests. E2B is the better voice starting point.")
        if (tested) return ModelFit("Tested on your phone",
            "This installed model passed a reply test here. That is stronger evidence than a memory estimate; it doesn't measure chat quality or speed.")
        if (phone.totalRamBytes <= 0 || spec.downloadBytes == null)
            return ModelFit("Try the model test", "Not enough information for a memory estimate. Start with a small model and run its test.")
        if (estimatedWorkingBytes(spec) > phone.totalRamBytes - GIB)
            return ModelFit("Large · test first", "A large model for this phone's total memory. It may run slowly or fail to load; the model test will check. This size estimate is not a measured limit.")
        // Android can reclaim cached memory, and the current model may already occupy RAM.
        // Subtracting a free-RAM snapshot would count that model's memory twice.
        if (spec.id == "Qwen3-1.7B" || (spec.id == "Qwen3-0.6B" && phone.totalRamBytes < 7 * GIB))
            return ModelFit("Good starting point", "A small model to try on this phone. Run its model test to check that it can reply.", true)
        if (spec.reasoning) return ModelFit("Slower, more thinking", "Better suited to patient text chats; it may pause before answering. Run its model test first.")
        return ModelFit("Worth trying", "Its size looks reasonable for this phone's total memory. Run the model test to check loading and a reply.")
    }
}
