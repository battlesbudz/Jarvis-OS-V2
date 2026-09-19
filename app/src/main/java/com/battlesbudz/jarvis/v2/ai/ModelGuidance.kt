package com.battlesbudz.jarvis.v2.ai

/** Conservative estimates, not benchmark results or a guarantee of driver compatibility. */
data class PhoneProfile(
    val name: String, val model: String, val chip: String,
    val totalRamBytes: Long, val availableRamBytes: Long, val freeStorageBytes: Long,
    val arm64: Boolean
)
data class ModelFit(val label: String, val explanation: String, val recommended: Boolean = false)

object ModelGuidance {
    private const val GIB = 1_073_741_824L
    fun estimatedWorkingBytes(spec: LocalModelSpec): Long =
        ((spec.downloadBytes ?: 3 * GIB) * if (spec.recommendedGpu) 1.8 else 1.4).toLong() + GIB

    fun assess(spec: LocalModelSpec, phone: PhoneProfile, installed: Boolean = false): ModelFit {
        if (!phone.arm64) return ModelFit("Unsupported phone", "This app needs a 64-bit ARM phone.")
        val bytes = spec.downloadBytes
        if (!installed && bytes != null && phone.freeStorageBytes < bytes + GIB)
            return ModelFit("Free storage needed", "Free space before downloading this model. Leave room for model caches too.")
        val working = estimatedWorkingBytes(spec)
        if (phone.totalRamBytes <= 0) return ModelFit("Try on this phone", "Memory information is unavailable; start with a small model.")
        if (working > phone.totalRamBytes - 2 * GIB)
            return ModelFit("Likely too large", "May close the app or run very slowly on this phone. A smaller model is a better starting point.")
        if (phone.availableRamBytes > 0 && working > phone.availableRamBytes)
            return ModelFit("Memory may be tight", "Estimated working memory exceeds what is free now. Close other apps or choose a smaller model.")
        if (spec.id == "Gemma-4-E4B-it" && phone.model.startsWith("SM-F956", true))
            return ModelFit("Slower on Fold6", "E4B has been slow in your Fold6 tests. E2B is the better voice starting point.")
        if (spec.id == "Gemma-4-E2B-it" && phone.model.startsWith("SM-F956", true))
            return ModelFit("Recommended for voice", "A practical starting point based on your Fold6 tests; speed still depends on heat and free memory.", true)
        if (spec.id == "Qwen3-1.7B" || (spec.id == "Qwen3-0.6B" && phone.totalRamBytes < 7 * GIB))
            return ModelFit("Good starting point", "Small enough for this phone's memory estimate. Run the built-in model test to check it.", true)
        if (spec.reasoning) return ModelFit("Slower, more thinking", "Better suited to patient text chats; it may pause before answering.")
        return ModelFit("Likely to fit", "Memory estimate looks reasonable. Phone drivers and model behavior still need the built-in test.")
    }
}
