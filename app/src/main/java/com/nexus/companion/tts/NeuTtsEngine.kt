package com.nexus.companion.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NeuTTS Air engine using GGUF model via llama.cpp JNI.
 * Generates audio tokens from text, then decodes via NeuCodec.
 *
 * Falls back to Android TTS if model not available.
 */
class NeuTtsEngine(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var isInitialized = false
    private var fallbackTts: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    private val ttsModelFile: File
        get() = File(context.cacheDir, "models/neutts-nano-q4.gguf")

    private val codecModelFile: File
        get() = File(context.cacheDir, "models/neucodec.gguf")

    fun initialize() {
        // Initialize Android TTS as fallback
        fallbackTts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                fallbackTts?.language = java.util.Locale.GERMAN
                fallbackReady = true
            }
        }

        // Initialize AudioTrack for NeuTTS
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

        isInitialized = ttsModelFile.exists() && codecModelFile.exists()
    }

    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            // Use Android TTS fallback
            if (fallbackReady) {
                fallbackTts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nexus_tts")
            }
            return@withContext
        }

        // TODO: When NeuTTS GGUF models are downloaded, use llama.cpp to generate
        // audio tokens and decode with NeuCodec. For now, use fallback.
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
        isInitialized = false
    }

    fun isAvailable(): Boolean = isInitialized || fallbackReady
}
