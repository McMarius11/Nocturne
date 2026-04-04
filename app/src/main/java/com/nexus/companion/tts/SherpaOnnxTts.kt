package com.nexus.companion.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.nexus.companion.llm.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Neural TTS engine using sherpa-onnx.
 * Supports Kokoro (EN, high quality) and Piper/VITS (DE, medium quality).
 *
 * Requires pre-built native libs in jniLibs/arm64-v8a/:
 * - libsherpa-onnx-jni.so
 * - libonnxruntime.so
 */
class SherpaOnnxTts {

    companion object {
        private const val TAG = "SherpaOnnxTts"

        fun isLibraryAvailable(): Boolean {
            return try {
                System.loadLibrary("sherpa-onnx-jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "sherpa-onnx-jni not available")
                false
            }
        }
    }

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var currentModelType: String? = null // "kokoro" or "piper"

    val isReady: Boolean get() = tts != null

    /**
     * Load Kokoro TTS model (English, high quality).
     * @param modelDir directory containing model.onnx, voices.bin, tokens.txt
     * @param dataDir path to espeak-ng-data directory
     */
    fun loadKokoro(modelDir: File, dataDir: String): Boolean {
        release()
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(modelDir, "model.onnx").absolutePath,
                        voices = File(modelDir, "voices.bin").absolutePath,
                        tokens = File(modelDir, "tokens.txt").absolutePath,
                        dataDir = dataDir,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                )
            )
            tts = OfflineTts(config = config)
            currentModelType = "kokoro"
            val sr = tts!!.sampleRate()
            val ns = tts!!.numSpeakers()
            DebugLog.tts("Kokoro loaded: sampleRate=$sr, speakers=$ns")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Kokoro", e)
            DebugLog.tts("Kokoro load failed: ${e.message}")
            tts = null
            return false
        }
    }

    /**
     * Load Piper/VITS TTS model (German, medium quality).
     * @param modelPath path to the .onnx model file
     * @param dataDir path to espeak-ng-data directory
     */
    fun loadPiper(modelPath: File, dataDir: String): Boolean {
        release()
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelPath.absolutePath,
                        tokens = File(modelPath.parent, "tokens.txt").absolutePath,
                        dataDir = dataDir,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                )
            )
            tts = OfflineTts(config = config)
            currentModelType = "piper"
            val sr = tts!!.sampleRate()
            DebugLog.tts("Piper loaded: sampleRate=$sr")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Piper", e)
            DebugLog.tts("Piper load failed: ${e.message}")
            tts = null
            return false
        }
    }

    /**
     * Generate speech and play it. Blocks until playback is complete.
     */
    suspend fun speak(text: String, speakerId: Int = 0, speed: Float = 1.0f) = withContext(Dispatchers.IO) {
        val engine = tts ?: run {
            Log.w(TAG, "TTS not loaded")
            return@withContext
        }

        try {
            val startMs = System.currentTimeMillis()
            val audio = engine.generate(text, sid = speakerId, speed = speed)
            val genMs = System.currentTimeMillis() - startMs

            if (audio.samples.isEmpty()) {
                DebugLog.tts("Empty audio for: ${text.take(50)}")
                return@withContext
            }

            DebugLog.tts("Generated ${audio.samples.size} samples in ${genMs}ms (${text.length} chars, ${audio.sampleRate}Hz)")

            playAudio(audio.samples, audio.sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
            DebugLog.tts("Speak error: ${e.message}")
        }
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack?.release()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcm, 0, pcm.size)
        audioTrack?.play()

        // Wait for playback (using delay instead of Thread.sleep to avoid blocking dispatcher)
        val durationMs = (samples.size * 1000L) / sampleRate
        kotlinx.coroutines.delay(durationMs + 200)
        audioTrack?.stop()
    }

    fun stop() {
        try { audioTrack?.stop() } catch (_: Exception) {}
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        tts?.free()
        tts = null
        currentModelType = null
    }

    fun getNumSpeakers(): Int = tts?.numSpeakers() ?: 0
}
