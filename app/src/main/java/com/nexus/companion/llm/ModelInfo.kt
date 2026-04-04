package com.nexus.companion.llm

enum class PromptFormat {
    ALPACA,      // ### Instruction / ### Input / ### Response (Noromaid, MythoMax)
    GEMMA,       // <start_of_turn>user\n...<end_of_turn> (Gemma 3/4 — has system turn)
    CHATML       // <|im_start|>system\n...<|im_end|> (Qwen3)
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
    val promptFormat: PromptFormat = PromptFormat.ALPACA,
    val contextLength: Int = 8192
) {
    companion object {
        // --- Recommended: Gemma 4 E4B ---

        val GEMMA4_E4B_UNCENSORED = ModelInfo(
            id = "gemma4-e4b-uncensored",
            displayName = "Gemma 4 E4B Unzensiert",
            fileName = "Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive/resolve/main/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf",
            sizeGb = 5.34f,
            sizeBytes = 5_340_000_000L,
            batteryPerHour = 12,
            description = "Empfohlen — Gemma 4, unzensiert, multilingual (5 GB)",
            promptFormat = PromptFormat.GEMMA,
            contextLength = 8192
        )

        val GEMMA4_E4B = ModelInfo(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B",
            fileName = "google_gemma-4-E4B-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/google_gemma-4-E4B-it-GGUF/resolve/main/google_gemma-4-E4B-it-Q4_K_M.gguf",
            sizeGb = 5.0f,
            sizeBytes = 5_000_000_000L,
            batteryPerHour = 12,
            description = "Gemma 4, offiziell, multilingual (5 GB)",
            promptFormat = PromptFormat.GEMMA,
            contextLength = 8192
        )

        // --- Uncensored / Abliterated ---

        val QWEN3_4B_ABLITERATED = ModelInfo(
            id = "qwen3-4b-abliterated",
            displayName = "Qwen3 4B Abliterated",
            fileName = "mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/mlabonne_Qwen3-4B-abliterated-GGUF/resolve/main/mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf",
            sizeGb = 2.7f,
            sizeBytes = 2_900_000_000L,
            batteryPerHour = 8,
            description = "Thinking-Modus, unzensiert, kleiner (2.7 GB)",
            promptFormat = PromptFormat.CHATML,
            contextLength = 8192
        )

        // --- Roleplay / Companion models ---

        val NOROMAID_7B = ModelInfo(
            id = "noromaid-7b",
            displayName = "Noromaid 7B",
            fileName = "Mistral-Noromaid-7B-1500.q5_k_m.gguf",
            downloadUrl = "https://huggingface.co/NeverSleep/Noromaid-7b-v0.1.1-GGUF/resolve/main/Mistral-Noromaid-7B-1500.q5_k_m.gguf",
            sizeGb = 5.1f,
            sizeBytes = 5_500_000_000L,
            batteryPerHour = 13,
            description = "Roleplay — warm und romantisch (5.1 GB)",
            promptFormat = PromptFormat.ALPACA,
            contextLength = 8192
        )

        val MYTHOMAX_13B = ModelInfo(
            id = "mythomax-13b",
            displayName = "MythoMax 13B",
            fileName = "mythomax-l2-13b.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/MythoMax-L2-13B-GGUF/resolve/main/mythomax-l2-13b.Q4_K_M.gguf",
            sizeGb = 7.9f,
            sizeBytes = 8_500_000_000L,
            batteryPerHour = 25,
            description = "Beste Qualität — ausdrucksstark (7.9 GB)",
            promptFormat = PromptFormat.ALPACA,
            contextLength = 4096
        )

        val ALL_MODELS = listOf(
            GEMMA4_E4B_UNCENSORED,
            GEMMA4_E4B,
            QWEN3_4B_ABLITERATED,
            NOROMAID_7B,
            MYTHOMAX_13B
        )

        fun findById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
    }
}
