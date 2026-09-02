package com.battlesbudz.jarvis.v2.ai

data class LocalModelSpec(
    val id: String,
    val fileName: String,
    val expectedSha256: String? = null,
    val recommendedGpu: Boolean
)

object ModelCatalog {
    val gemma4E2b = LocalModelSpec(
        id = "Gemma-4-E2B-it",
        fileName = "gemma-4-E2B-it.litertlm",
        recommendedGpu = true
    )

    val mobileActions270m = LocalModelSpec(
        id = "MobileActions-270M",
        fileName = "functiongemma-270m-ft-mobile-actions.litertlm",
        recommendedGpu = false
    )
}