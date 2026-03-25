package com.nexus.companion.tts

/**
 * JNI bridge for TTS inference via llama.cpp.
 * Supports OuteTTS (with WavTokenizer vocoder) and will support CSM when available.
 *
 * The TTS pipeline:
 * 1. Load TTS model (OuteTTS LLM that generates audio tokens)
 * 2. Load vocoder model (WavTokenizer that converts audio tokens → PCM)
 * 3. Generate speech: text → audio tokens → PCM float samples
 */
class TtsJni {
    companion object {
        init {
            System.loadLibrary("nexus-llama")
        }
    }

    /**
     * Load TTS model and vocoder for speech synthesis.
     * @param ttsModelPath Path to OuteTTS model GGUF
     * @param vocoderPath Path to WavTokenizer vocoder GGUF
     * @param nThreads Number of inference threads
     * @return true if both models loaded successfully
     */
    external fun loadTtsModel(ttsModelPath: String, vocoderPath: String, nThreads: Int): Boolean

    /**
     * Generate speech from text.
     * @param text The text to speak
     * @param speakerId Speaker ID (e.g. "en_male_1", "de_female_1")
     * @return PCM float samples (mono, 24kHz) or empty array on failure
     */
    external fun generateSpeech(text: String, speakerId: String): FloatArray

    /**
     * Check if TTS model is loaded.
     */
    external fun isTtsModelLoaded(): Boolean

    /**
     * Unload TTS model and vocoder, freeing memory.
     */
    external fun unloadTtsModel()

    /**
     * Abort ongoing TTS generation.
     */
    external fun abortTts()
}
