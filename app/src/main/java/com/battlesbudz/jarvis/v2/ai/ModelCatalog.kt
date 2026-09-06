package com.battlesbudz.jarvis.v2.ai

data class LocalModelSpec(
    val id: String,
    val fileName: String,
    val expectedSha256: String? = null,
    val recommendedGpu: Boolean,
    val downloadUrl: String? = null
)

object ModelCatalog {
    val gemma4E2b = LocalModelSpec(
        id = "Gemma-4-E2B-it",
        fileName = "gemma-4-E2B-it.litertlm",
        expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        recommendedGpu = true,
        // Pin the download to the exact Hugging Face revision used by PR1.
        // Updating the model requires an intentional catalog change.
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1/gemma-4-E2B-it.litertlm?download=true"
    )

}
