package com.nexus.companion.tts

/**
 * Available TTS models.
 *
 * Kokoro: High-quality neural TTS, English only, 82M params, ~346 MB ONNX.
 *         Uses sherpa-onnx runtime (not llama.cpp).
 *
 * Piper (thorsten): Medium-quality neural TTS, German, ~30 MB ONNX.
 *                   Uses sherpa-onnx VITS runtime.
 *
 * LFM2.5-Audio: End-to-end Audio-LM (prepared, pending llama.cpp PR #18641).
 *
 * Sesame CSM: Conversational speech (experimental, pending llama.cpp Issue #12392).
 */
data class TtsModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val languages: List<String>,
    val description: String,
    val engine: TtsEngine = TtsEngine.SYSTEM
) {
    enum class TtsEngine {
        SYSTEM,      // Android System TTS
        KOKORO,      // sherpa-onnx Kokoro
        PIPER,       // sherpa-onnx Piper/VITS
        LFM25,       // llama.cpp LFM2.5-Audio (future)
        CSM          // llama.cpp Sesame CSM (future)
    }

    companion object {

        // ===== Kokoro (English, high quality) =====
        // sherpa-onnx compatible, downloaded as tar.bz2 from GitHub releases

        val KOKORO_EN = TtsModelInfo(
            id = "kokoro-en",
            displayName = "Kokoro EN (Neural)",
            fileName = "kokoro-en-v0_19.tar.bz2",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
            sizeBytes = 346_000_000L,
            languages = listOf("en"),
            description = "Hohe Qualität — Englisch, 11 Stimmen (346 MB)",
            engine = TtsEngine.KOKORO
        )

        // ===== Piper thorsten (German, medium quality) =====
        // VITS model, downloaded from HuggingFace

        val PIPER_DE_THORSTEN = TtsModelInfo(
            id = "piper-de-thorsten",
            displayName = "Piper Thorsten DE (Neural)",
            fileName = "vits-piper-de_DE-thorsten_emotional-medium.tar.bz2",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-thorsten_emotional-medium.tar.bz2",
            sizeBytes = 75_000_000L,
            languages = listOf("de"),
            description = "Gute Qualität — Deutsch, emotional (75 MB)",
            engine = TtsEngine.PIPER
        )

        // ===== LFM2.5-Audio (future — pending llama.cpp PR #18641) =====

        val LFM25_AUDIO_MODEL = TtsModelInfo(
            id = "lfm25-audio",
            displayName = "LFM2.5 Audio (1.5B)",
            fileName = "LFM2.5-Audio-1.5B-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/main/LFM2.5-Audio-1.5B-Q4_0.gguf",
            sizeBytes = 696_000_000L,
            languages = listOf("de", "en", "ar", "zh", "fr", "ja", "ko", "es"),
            description = "End-to-End Audio — DE+EN, ASR+TTS+Chat (696 MB)",
            engine = TtsEngine.LFM25
        )

        // ===== Sesame CSM (experimental — pending llama.cpp Issue #12392) =====

        val SESAME_CSM_BACKBONE = TtsModelInfo(
            id = "sesame-csm-backbone",
            displayName = "Sesame CSM Backbone",
            fileName = "sesame-csm-backbone.gguf",
            downloadUrl = "https://huggingface.co/ggml-org/sesame-csm-1b-GGUF/resolve/main/sesame-csm-backbone.gguf",
            sizeBytes = 900_000_000L,
            languages = listOf("en"),
            description = "Sesame CSM Backbone — 1B Parameter",
            engine = TtsEngine.CSM
        )

        // ===== Models shown in UI for download =====

        val ALL_TTS_MODELS = listOf(
            KOKORO_EN,
            PIPER_DE_THORSTEN
        )

        /** Experimental models (shown in a separate section) */
        val EXPERIMENTAL_MODELS = listOf(
            LFM25_AUDIO_MODEL,
            SESAME_CSM_BACKBONE
        )

        fun findById(id: String): TtsModelInfo? {
            return ALL_TTS_MODELS.find { it.id == id }
                ?: EXPERIMENTAL_MODELS.find { it.id == id }
        }
    }
}
