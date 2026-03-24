package com.nexus.companion.llm

enum class PromptFormat {
    ALPACA,      // ### Instruction / ### Input / ### Response (Noromaid, MythoMax)
    GEMMA,       // <start_of_turn>user\n...<end_of_turn>
    CHATML       // <|im_start|>system\n...<|im_end|>
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeGb: Float,
    val sizeBytes: Long,
    val batteryPerHour: Int,
    val description: String,
    val promptFormat: PromptFormat = PromptFormat.ALPACA
) {
    companion object {
        val NOROMAID_7B = ModelInfo(
            id = "noromaid-7b",
            displayName = "Noromaid 7B",
            fileName = "Mistral-Noromaid-7B-1500.q5_k_m.gguf",
            downloadUrl = "https://huggingface.co/NeverSleep/Noromaid-7b-v0.1.1-GGUF/resolve/main/Mistral-Noromaid-7B-1500.q5_k_m.gguf",
            sizeGb = 5.1f,
            sizeBytes = 5_500_000_000L,
            batteryPerHour = 13,
            description = "Standard — warm und romantisch",
            promptFormat = PromptFormat.ALPACA
        )

        val MYTHOMAX_13B = ModelInfo(
            id = "mythomax-13b",
            displayName = "MythoMax 13B",
            fileName = "mythomax-l2-13b.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/MythoMax-L2-13B-GGUF/resolve/main/mythomax-l2-13b.Q4_K_M.gguf",
            sizeGb = 7.9f,
            sizeBytes = 8_500_000_000L,
            batteryPerHour = 25,
            description = "Beste Qualität — ausdrucksstark",
            promptFormat = PromptFormat.ALPACA
        )

        val GEMMA_2B = ModelInfo(
            id = "gemma-2b",
            displayName = "Gemma 2B",
            fileName = "gemma-2b-it-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            sizeGb = 1.6f,
            sizeBytes = 1_700_000_000L,
            batteryPerHour = 6,
            description = "Akkusparer — leicht und schnell",
            promptFormat = PromptFormat.GEMMA
        )

        val ALL_MODELS = listOf(NOROMAID_7B, MYTHOMAX_13B, GEMMA_2B)

        fun findById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
    }
}
