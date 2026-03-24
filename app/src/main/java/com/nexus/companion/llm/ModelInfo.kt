package com.nexus.companion.llm

data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeGb: Float,
    val batteryPerHour: Int,
    val description: String
) {
    companion object {
        val NOROMAID_7B = ModelInfo(
            id = "noromaid-7b",
            displayName = "Noromaid 7B",
            fileName = "noromaid-7b-v0.1.1.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/Noromaid-7B-v0.1.1-GGUF/resolve/main/noromaid-7b-v0.1.1.Q4_K_M.gguf",
            sizeGb = 4.1f,
            batteryPerHour = 13,
            description = "Standard — warm und romantisch"
        )

        val MYTHOMAX_13B = ModelInfo(
            id = "mythomax-13b",
            displayName = "MythoMax 13B",
            fileName = "mythomax-l2-13b.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/MythoMax-L2-13B-GGUF/resolve/main/mythomax-l2-13b.Q4_K_M.gguf",
            sizeGb = 7.9f,
            batteryPerHour = 25,
            description = "Beste Qualität — ausdrucksstark"
        )

        val GEMMA_2B = ModelInfo(
            id = "gemma-2b",
            displayName = "Gemma 2B",
            fileName = "gemma-2b-it.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/google/gemma-2b-it-GGUF/resolve/main/gemma-2b-it.Q4_K_M.gguf",
            sizeGb = 1.5f,
            batteryPerHour = 6,
            description = "Akkusparer — leicht und schnell"
        )

        val ALL_MODELS = listOf(NOROMAID_7B, MYTHOMAX_13B, GEMMA_2B)

        fun findById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
    }
}
