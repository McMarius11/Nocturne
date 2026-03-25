package com.nexus.companion.tts

/**
 * Available TTS models for neural voice synthesis.
 *
 * NeuTTS Nano: Purpose-built for mobile, separate models per language (~195 MB each).
 * Kokoro: Multilingual (v1.0+), single model, ~198 MB, DE+EN in one file.
 * OuteTTS: Multilingual, larger but high quality, DE+EN+4 more languages.
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
        // --- NeuTTS Nano (best for mobile, already integrated in NeuTtsEngine) ---

        val NEUTTS_NANO_EN = TtsModelInfo(
            id = "neutts-nano-en",
            displayName = "NeuTTS Nano (English)",
            fileName = "neutts-nano-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/neuphonic/neutts-nano-q4-gguf/resolve/main/neutts-nano-Q4_0.gguf",
            sizeBytes = 195_000_000L,
            languages = listOf("en"),
            description = "Englisch — ultraleicht, optimiert für Mobile"
        )

        val NEUTTS_NANO_DE = TtsModelInfo(
            id = "neutts-nano-de",
            displayName = "NeuTTS Nano (Deutsch)",
            fileName = "neutts-nano-german-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/neuphonic/neutts-nano-german-q4-gguf/resolve/main/neutts-nano-german-Q4_0.gguf",
            sizeBytes = 195_000_000L,
            languages = listOf("de"),
            description = "Deutsch — ultraleicht, optimiert für Mobile"
        )

        // --- Kokoro (single model, multilingual DE+EN) ---

        val KOKORO_82M = TtsModelInfo(
            id = "kokoro-82m",
            displayName = "Kokoro 82M",
            fileName = "Kokoro_no_espeak_Q4.gguf",
            downloadUrl = "https://huggingface.co/mmwillet2/Kokoro_GGUF/resolve/main/Kokoro_no_espeak_Q4.gguf",
            sizeBytes = 198_000_000L,
            languages = listOf("en", "de"),
            description = "Deutsch + Englisch — ein Modell, 82M Parameter"
        )

        // --- OuteTTS (high quality multilingual) ---

        val OUTETTS_500M = TtsModelInfo(
            id = "outetts-0.3-500m",
            displayName = "OuteTTS 0.3 (500M)",
            fileName = "OuteTTS-0.3-500M-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/OuteAI/OuteTTS-0.3-500M-GGUF/resolve/main/OuteTTS-0.3-500M-Q4_K_M.gguf",
            sizeBytes = 403_000_000L,
            languages = listOf("de", "en", "fr", "jp", "ko", "zh"),
            description = "Deutsch + Englisch + 4 Sprachen — 500M"
        )

        val OUTETTS_1B = TtsModelInfo(
            id = "outetts-0.3-1b",
            displayName = "OuteTTS 0.3 (1B)",
            fileName = "OuteTTS-0.3-1B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/OuteAI/OuteTTS-0.3-1B-GGUF/resolve/main/OuteTTS-0.3-1B-Q4_K_M.gguf",
            sizeBytes = 800_000_000L,
            languages = listOf("de", "en", "fr", "jp", "ko", "zh"),
            description = "Deutsch + Englisch — beste Qualität, 1B"
        )

        // --- Sesame CSM (highest quality, experimental — pending llama.cpp support) ---

        val SESAME_CSM_BACKBONE = TtsModelInfo(
            id = "sesame-csm-backbone",
            displayName = "Sesame CSM Backbone",
            fileName = "sesame-csm-backbone.gguf",
            downloadUrl = "https://huggingface.co/ggml-org/sesame-csm-1b-GGUF/resolve/main/sesame-csm-backbone.gguf",
            sizeBytes = 900_000_000L,
            languages = listOf("en"),
            description = "Sesame CSM Backbone — 1B Parameter (benötigt Codec)"
        )

        val SESAME_CSM_DECODER = TtsModelInfo(
            id = "sesame-csm-decoder",
            displayName = "Sesame CSM Decoder",
            fileName = "sesame-csm-decoder0.gguf",
            downloadUrl = "https://huggingface.co/ggml-org/sesame-csm-1b-GGUF/resolve/main/sesame-csm-decoder0.gguf",
            sizeBytes = 100_000_000L,
            languages = listOf("en"),
            description = "Sesame CSM Audio-Decoder — 100M Parameter"
        )

        val SESAME_MIMI_CODEC = TtsModelInfo(
            id = "sesame-mimi-codec",
            displayName = "Mimi Audio Codec",
            fileName = "kyutai-mimi.gguf",
            downloadUrl = "https://huggingface.co/ggml-org/sesame-csm-1b-GGUF/resolve/main/kyutai-mimi.gguf",
            sizeBytes = 226_000_000L,
            languages = listOf("en"),
            description = "Kyutai Mimi Codec — Audio-Dekodierung für CSM"
        )

        val ALL_TTS_MODELS = listOf(
            OUTETTS_500M,
            OUTETTS_1B,
            KOKORO_82M,
            NEUTTS_NANO_EN,
            NEUTTS_NANO_DE
        )

        /** CSM components (separate from ALL_TTS_MODELS — experimental) */
        val CSM_COMPONENTS = listOf(
            SESAME_CSM_BACKBONE,
            SESAME_CSM_DECODER,
            SESAME_MIMI_CODEC
        )

        fun findById(id: String): TtsModelInfo? = ALL_TTS_MODELS.find { it.id == id }
    }
}
