package com.nexus.companion.tts

/**
 * Available TTS models. OuteTTS 0.3 supports DE + EN + FR + JP + KO + ZH.
 * Kokoro is English-focused with experimental German support.
 */
data class TtsModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val languages: List<String>,
    val description: String
) {
    companion object {
        val OUTETSS_500M = TtsModelInfo(
            id = "outetts-0.3-500m",
            displayName = "OuteTTS 0.3 (500M)",
            fileName = "OuteTTS-0.3-500M-Q8_0.gguf",
            downloadUrl = "https://huggingface.co/OuteAI/OuteTTS-0.3-500M-GGUF/resolve/main/OuteTTS-0.3-500M-Q8_0.gguf",
            sizeBytes = 530_000_000L,
            languages = listOf("de", "en", "fr", "jp", "ko", "zh"),
            description = "Deutsch + Englisch — leicht, 500M Parameter"
        )

        val OUTETSS_1B = TtsModelInfo(
            id = "outetts-0.3-1b",
            displayName = "OuteTTS 0.3 (1B)",
            fileName = "OuteTTS-0.3-1B-Q8_0.gguf",
            downloadUrl = "https://huggingface.co/OuteAI/OuteTTS-0.3-1B-GGUF/resolve/main/OuteTTS-0.3-1B-Q8_0.gguf",
            sizeBytes = 1_200_000_000L,
            languages = listOf("de", "en", "fr", "jp", "ko", "zh"),
            description = "Deutsch + Englisch — beste Qualität, 1B Parameter"
        )

        val KOKORO_82M = TtsModelInfo(
            id = "kokoro-82m",
            displayName = "Kokoro 82M",
            fileName = "kokoro-v1.0-Q8_0.gguf",
            downloadUrl = "https://huggingface.co/mmwillet2/Kokoro_GGUF/resolve/main/kokoro-v1.0-Q8_0.gguf",
            sizeBytes = 90_000_000L,
            languages = listOf("en"),
            description = "Englisch — ultraleicht, 82M Parameter"
        )

        val ALL_TTS_MODELS = listOf(OUTETSS_500M, OUTETSS_1B, KOKORO_82M)

        fun findById(id: String): TtsModelInfo? = ALL_TTS_MODELS.find { it.id == id }
    }
}
