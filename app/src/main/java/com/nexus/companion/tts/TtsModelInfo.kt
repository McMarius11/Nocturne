package com.nexus.companion.tts

/**
 * Available audio/TTS models.
 *
 * LFM2.5-Audio: End-to-end audio-language model (ASR+TTS+Chat in one model).
 *               Requires 4 GGUF files. llama.cpp support pending PR #18641.
 *               DE+EN+6 languages, 1.5B params, ~855 MB total (Q4_0).
 *
 * Sesame CSM:   Conversational speech model, highest quality voice.
 *               Requires 3 GGUF files. llama.cpp support pending Issue #12392.
 *               EN only, 1B params.
 *
 * Other models: OuteTTS, Kokoro, NeuTTS — various compatibility levels.
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

        // ===== Liquid AI LFM2.5-Audio (best option — pending llama.cpp PR #18641) =====
        // End-to-end: Audio-In + Audio-Out, no separate ASR/TTS needed.
        // 8x faster than Sesame's Mimi on mobile CPU.

        val LFM25_AUDIO_MODEL = TtsModelInfo(
            id = "lfm25-audio",
            displayName = "LFM2.5 Audio (1.5B)",
            fileName = "LFM2.5-Audio-1.5B-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/main/LFM2.5-Audio-1.5B-Q4_0.gguf",
            sizeBytes = 696_000_000L,
            languages = listOf("de", "en", "ar", "zh", "fr", "ja", "ko", "es"),
            description = "End-to-End Audio — DE+EN, ASR+TTS+Chat in einem Modell (696 MB)"
        )

        val LFM25_AUDIO_MMPROJ = TtsModelInfo(
            id = "lfm25-mmproj",
            displayName = "LFM2.5 Audio-Projektor",
            fileName = "mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/main/mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf",
            sizeBytes = 50_500_000L,
            languages = listOf("de", "en"),
            description = "Audio-Projektor für LFM2.5 (51 MB)"
        )

        val LFM25_AUDIO_VOCODER = TtsModelInfo(
            id = "lfm25-vocoder",
            displayName = "LFM2.5 Vocoder",
            fileName = "vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/main/vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf",
            sizeBytes = 109_000_000L,
            languages = listOf("de", "en"),
            description = "Audio-Decoder/Vocoder für LFM2.5 (109 MB)"
        )

        val LFM25_AUDIO_TOKENIZER = TtsModelInfo(
            id = "lfm25-tokenizer",
            displayName = "LFM2.5 Speaker-Datei",
            fileName = "tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/main/tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf",
            sizeBytes = 5_000_000L,
            languages = listOf("de", "en"),
            description = "Speaker/Tokenizer für LFM2.5"
        )

        // ===== Sesame CSM (experimental — pending llama.cpp Issue #12392) =====
        // Highest quality voice, but EN only and still in draft.

        val SESAME_CSM_BACKBONE = TtsModelInfo(
            id = "sesame-csm-backbone",
            displayName = "Sesame CSM Backbone",
            fileName = "sesame-csm-backbone.gguf",
            downloadUrl = "https://huggingface.co/ggml-org/sesame-csm-1b-GGUF/resolve/main/sesame-csm-backbone.gguf",
            sizeBytes = 900_000_000L,
            languages = listOf("en"),
            description = "Sesame CSM Backbone — 1B Parameter"
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

        // ===== Models shown in UI for download =====

        val ALL_TTS_MODELS = listOf(
            LFM25_AUDIO_MODEL   // Primary recommendation
        )

        /** LFM2.5 companion files (auto-downloaded with main model) */
        val LFM25_COMPONENTS = listOf(
            LFM25_AUDIO_MMPROJ,
            LFM25_AUDIO_VOCODER,
            LFM25_AUDIO_TOKENIZER
        )

        /** Sesame CSM components (experimental) */
        val CSM_COMPONENTS = listOf(
            SESAME_CSM_BACKBONE,
            SESAME_CSM_DECODER,
            SESAME_MIMI_CODEC
        )

        /** All experimental models (shown in a separate section) */
        val EXPERIMENTAL_MODELS = listOf(
            SESAME_CSM_BACKBONE
        )

        fun findById(id: String): TtsModelInfo? {
            return ALL_TTS_MODELS.find { it.id == id }
                ?: LFM25_COMPONENTS.find { it.id == id }
                ?: CSM_COMPONENTS.find { it.id == id }
        }
    }
}
