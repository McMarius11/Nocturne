package com.nexus.companion.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Engine for neural TTS via llama.cpp.
 * Currently supports OuteTTS (text → audio tokens → PCM via WavTokenizer vocoder).
 * Sesame CSM support will be added when llama.cpp integrates it natively.
 *
 * Pipeline: Text → OuteTTS (audio code generation) → WavTokenizer (vocoder) → PCM → AudioTrack
 */
class CsmEngine(private val context: Context) {

    companion object {
        private const val TAG = "CsmEngine"
        private const val SAMPLE_RATE = 24000
    }

    private val jni = TtsJni()
    private var audioTrack: AudioTrack? = null
    private var isLoaded = false

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    fun initialize() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
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
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Load TTS model pair (OuteTTS + WavTokenizer vocoder).
     */
    suspend fun loadModel(ttsModel: TtsModelInfo, vocoderModel: TtsModelInfo): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val ttsFile = File(modelsDir, ttsModel.fileName)
                val vocoderFile = File(modelsDir, vocoderModel.fileName)

                if (!ttsFile.exists()) {
                    Log.e(TAG, "TTS model not found: ${ttsFile.absolutePath}")
                    return@withContext false
                }
                if (!vocoderFile.exists()) {
                    Log.e(TAG, "Vocoder model not found: ${vocoderFile.absolutePath}")
                    return@withContext false
                }

                val success = jni.loadTtsModel(
                    ttsModelPath = ttsFile.absolutePath,
                    vocoderPath = vocoderFile.absolutePath,
                    nThreads = 4
                )

                isLoaded = success
                if (success) {
                    Log.i(TAG, "TTS models loaded: ${ttsModel.id} + vocoder")
                } else {
                    Log.e(TAG, "Failed to load TTS models")
                }
                success
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native TTS library not available", e)
                false
            }
        }

    /**
     * Generate speech from text and play it through AudioTrack.
     * @param text Text to speak
     * @param speakerId OuteTTS speaker ID (e.g. "en_male_1", "de_female_1")
     */
    suspend fun speak(text: String, speakerId: String = "en_male_1") =
        withContext(Dispatchers.IO) {
            if (!isLoaded) {
                Log.w(TAG, "TTS not loaded, cannot speak")
                return@withContext
            }

            try {
                Log.d(TAG, "Generating speech: $text")
                val pcmSamples = jni.generateSpeech(text, speakerId)

                if (pcmSamples.isEmpty()) {
                    Log.w(TAG, "No audio generated")
                    return@withContext
                }

                Log.d(TAG, "Playing ${pcmSamples.size} samples (${pcmSamples.size / SAMPLE_RATE.toFloat()}s)")
                playAudio(pcmSamples)
            } catch (e: Exception) {
                Log.e(TAG, "Error generating speech", e)
            }
        }

    private fun playAudio(samples: FloatArray) {
        audioTrack?.let { track ->
            try {
                track.play()
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                // Wait for playback to finish
                track.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error", e)
            }
        }
    }

    fun stop() {
        jni.abortTts()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
    }

    fun isReady(): Boolean = isLoaded && jni.isTtsModelLoaded()

    fun shutdown() {
        stop()
        jni.unloadTtsModel()
        audioTrack?.release()
        audioTrack = null
        isLoaded = false
    }
}
