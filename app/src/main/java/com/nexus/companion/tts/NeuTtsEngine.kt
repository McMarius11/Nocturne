package com.nexus.companion.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nexus.companion.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS engine with 3 backends (priority order):
 * 1. CsmEngine (OuteTTS via llama.cpp) — neural, DE+EN, best quality
 * 2. NeuTTS Nano (planned) — neural, separate DE/EN models
 * 3. Android System TTS — fallback, always available
 */
class NeuTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "NeuTtsEngine"
    }

    private var audioTrack: AudioTrack? = null
    private var fallbackTts: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    // Neural TTS via llama.cpp (OuteTTS / CSM)
    private var csmEngine: CsmEngine? = null
    private var neuralTtsReady = false
    private var activeTtsModel: TtsModelInfo? = null

    private var currentProfile: VoiceProfile = VoiceProfile.ANDROID_DE

    /** Shared latch for TTS completion — reset on each speak() call */
    @Volatile
    private var completionLatch: java.util.concurrent.CountDownLatch? = null

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    fun initialize() {
        // Android System TTS (always available fallback)
        fallbackTts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                fallbackTts?.language = currentProfile.ttsLocale
                fallbackTts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Set listener ONCE (reuses completionLatch field)
                fallbackTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { completionLatch?.countDown() }
                    @Deprecated("Deprecated") override fun onError(id: String?) { completionLatch?.countDown() }
                })
                fallbackReady = true
                Log.d(TAG, "Android TTS ready")
            }
        }

        // Try to initialize neural TTS
        csmEngine = CsmEngine(context)
        csmEngine?.initialize()

        // Check if OuteTTS models are downloaded
        checkNeuralTtsAvailability()
    }

    /** Check if any neural TTS model is downloaded */
    private fun checkNeuralTtsAvailability() {
        activeTtsModel = TtsModelInfo.ALL_TTS_MODELS.firstOrNull { model ->
            File(modelsDir, model.fileName).exists()
        }
        neuralTtsReady = activeTtsModel != null

        if (neuralTtsReady) {
            Log.i(TAG, "Neural TTS model found: ${activeTtsModel!!.fileName}")
        } else {
            Log.d(TAG, "No neural TTS model, using Android TTS fallback")
        }
    }

    /** Set the active TTS model by ID (called when user selects in UI) */
    fun setTtsModel(modelId: String?) {
        if (modelId == null) {
            activeTtsModel = null
            neuralTtsReady = false
            csmEngine?.shutdown()
            return
        }
        val model = TtsModelInfo.findById(modelId)
        if (model != null && File(modelsDir, model.fileName).exists()) {
            activeTtsModel = model
            neuralTtsReady = true
            // Force reload on next speak
            csmEngine?.shutdown()
        }
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        currentProfile = profile
        fallbackTts?.language = profile.ttsLocale
    }

    fun getCurrentProfile(): VoiceProfile = currentProfile

    fun isNeuralTtsAvailable(): Boolean = neuralTtsReady

    /**
     * Try to load the neural TTS model if files are present but engine not ready.
     */
    suspend fun tryLoadNeuralTts() {
        if (csmEngine?.isReady() == true) return
        if (!neuralTtsReady || activeTtsModel == null) return

        try {
            val model = activeTtsModel!!
            // Neural TTS requires both a TTS model and a vocoder model.
            // Currently OuteTTS GGUF files don't include a separate vocoder,
            // so this will fail gracefully and fall back to system TTS.
            // Full neural TTS support requires vocoder integration in llama.cpp.
            val vocoderFile = java.io.File(modelsDir, "wavtokenizer-large-75.gguf")
            if (!vocoderFile.exists()) {
                Log.d(TAG, "Vocoder not found, neural TTS unavailable (using system TTS)")
                return
            }
            val vocoderModel = TtsModelInfo(
                id = "wavtokenizer", displayName = "WavTokenizer",
                fileName = "wavtokenizer-large-75.gguf", downloadUrl = "",
                sizeBytes = 0, languages = emptyList(), description = ""
            )
            val success = csmEngine?.loadModel(model, vocoderModel) ?: false
            if (success) {
                Log.i(TAG, "Neural TTS model loaded: ${model.id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load neural TTS model", e)
        }
    }

    /**
     * Speak text using the best available TTS backend.
     * Priority: Neural TTS (OuteTTS) > Android System TTS
     */
    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        // Try neural TTS first — load on first use if needed
        if (neuralTtsReady) {
            if (csmEngine?.isReady() != true) {
                tryLoadNeuralTts()
            }
            if (csmEngine?.isReady() == true) {
                try {
                    val speakerId = when {
                        currentProfile.ttsLocale.language == "de" -> "de_female_1"
                        else -> "en_male_1"
                    }
                    csmEngine?.speak(text, speakerId)
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "Neural TTS failed, falling back to system TTS", e)
                }
            }
        }

        // Fallback to Android System TTS — wait for completion
        if (fallbackReady) {
            val utteranceId = "nexus_tts_${System.currentTimeMillis()}"
            completionLatch = java.util.concurrent.CountDownLatch(1)

            fallbackTts?.speak(
                text,
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

            // Wait for TTS to finish (max 30 seconds to prevent infinite block)
            completionLatch?.await(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    fun stop() {
        csmEngine?.stop()
        audioTrack?.stop()
        fallbackTts?.stop()
    }

    fun shutdown() {
        csmEngine?.shutdown()
        csmEngine = null
        audioTrack?.release()
        audioTrack = null
        fallbackTts?.shutdown()
        fallbackTts = null
        neuralTtsReady = false
    }

    fun isAvailable(): Boolean = neuralTtsReady || fallbackReady
}
