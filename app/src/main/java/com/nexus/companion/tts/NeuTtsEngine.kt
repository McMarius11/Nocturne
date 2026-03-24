package com.nexus.companion.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nexus.companion.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class NeuTtsEngine(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var neuTtsAvailable = false
    private var fallbackTts: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    private var currentProfile: VoiceProfile = VoiceProfile.ANDROID_DE

    private val ttsModelFile: File
        get() = File(context.cacheDir, "models/neutts-nano-q4.gguf")

    private val codecModelFile: File
        get() = File(context.cacheDir, "models/neucodec.gguf")

    fun initialize() {
        fallbackTts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                fallbackTts?.language = currentProfile.ttsLocale
                fallbackReady = true
            }
        }

        val sampleRate = 24000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        neuTtsAvailable = ttsModelFile.exists() && codecModelFile.exists()
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        currentProfile = profile
        fallbackTts?.language = profile.ttsLocale
    }

    fun getCurrentProfile(): VoiceProfile = currentProfile

    fun isNeuTtsAvailable(): Boolean = neuTtsAvailable

    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        if (currentProfile.isNeural && neuTtsAvailable) {
            // TODO: When NeuTTS GGUF models are downloaded, use llama.cpp to generate
            // audio tokens and decode with NeuCodec. For now, fall through to Android TTS.
        }

        if (fallbackReady) {
            fallbackTts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nexus_tts")
        }
    }

    fun stop() {
        audioTrack?.stop()
        fallbackTts?.stop()
    }

    fun shutdown() {
        audioTrack?.release()
        audioTrack = null
        fallbackTts?.shutdown()
        fallbackTts = null
        neuTtsAvailable = false
    }

    fun isAvailable(): Boolean = neuTtsAvailable || fallbackReady
}
