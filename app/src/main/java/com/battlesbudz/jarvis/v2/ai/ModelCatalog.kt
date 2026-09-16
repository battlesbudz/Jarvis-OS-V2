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

    val gemma4E4b = LocalModelSpec(
        id = "Gemma-4-E4B-it",
        fileName = "gemma-4-E4B-it.litertlm",
        expectedSha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
        recommendedGpu = true,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/1fc8912676889ed3aeec478c92c1e239bed08928/gemma-4-E4B-it.litertlm?download=true"
    )

    val all = listOf(gemma4E2b, gemma4E4b)

    fun find(id: String?): LocalModelSpec? = all.firstOrNull { it.id == id }

    // Preserve existing installations and recover safely from a removed catalog entry.
    fun resolve(id: String?): LocalModelSpec = find(id) ?: gemma4E2b
}
