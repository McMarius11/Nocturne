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

    private var currentProfile: VoiceProfile = VoiceProfile.ANDROID_DE

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    fun initialize() {
        // Android System TTS (always available fallback)
        fallbackTts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                fallbackTts?.language = currentProfile.ttsLocale
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

    private fun checkNeuralTtsAvailability() {
        val ttsModel = File(modelsDir, TtsModelInfo.OUTETTS_500M.fileName)
        // WavTokenizer vocoder is bundled with OuteTTS GGUF — check for the model file
        neuralTtsReady = ttsModel.exists()

        if (neuralTtsReady) {
            Log.i(TAG, "Neural TTS model found: ${ttsModel.name}")
        } else {
            Log.d(TAG, "No neural TTS model, using Android TTS fallback")
        }
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        currentProfile = profile
        fallbackTts?.language = profile.ttsLocale
    }

    fun getCurrentProfile(): VoiceProfile = currentProfile

    fun isNeuralTtsAvailable(): Boolean = neuralTtsReady

    /**
     * Speak text using the best available TTS backend.
     * Priority: Neural TTS (OuteTTS) > Android System TTS
     */
    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        // Try neural TTS first
        if (neuralTtsReady && csmEngine?.isReady() == true) {
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

        // Fallback to Android System TTS
        if (fallbackReady) {
            fallbackTts?.speak(
                text,
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                "nexus_tts"
            )
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
