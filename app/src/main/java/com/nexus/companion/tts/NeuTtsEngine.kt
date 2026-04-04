package com.nexus.companion.tts

import android.content.Context
import android.util.Log
import com.nexus.companion.VoiceProfile
import com.nexus.companion.llm.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * TTS engine with 3 backends (priority order):
 * 1. sherpa-onnx Kokoro (English, high quality neural TTS)
 * 2. sherpa-onnx Piper/VITS (German, medium quality neural TTS)
 * 3. Android System TTS (fallback, always available)
 *
 * Models are downloaded at runtime to app filesDir/tts-models/.
 * espeak-ng-data is bundled in assets or extracted on first use.
 */
class NeuTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "NeuTtsEngine"
    }

    private var fallbackTts: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    private var sherpaOnnx: SherpaOnnxTts? = null
    private var activeTtsModelId: String? = null

    private var currentProfile: VoiceProfile = VoiceProfile.ANDROID_DE

    @Volatile
    private var completionLatch: java.util.concurrent.CountDownLatch? = null

    private val ttsModelsDir: File
        get() = File(context.filesDir, "tts-models").also { it.mkdirs() }

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
                fallbackTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { completionLatch?.countDown() }
                    @Deprecated("Deprecated") override fun onError(id: String?) { completionLatch?.countDown() }
                })
                fallbackReady = true
                Log.d(TAG, "Android TTS ready")
            }
        }

        // Initialize sherpa-onnx if library is available
        if (SherpaOnnxTts.isLibraryAvailable()) {
            sherpaOnnx = SherpaOnnxTts()
            DebugLog.tts("sherpa-onnx library available")
        } else {
            DebugLog.tts("sherpa-onnx not available, using Android TTS only")
        }
    }

    fun setTtsModel(modelId: String?) {
        if (modelId == null) {
            activeTtsModelId = null
            sherpaOnnx?.release()
            DebugLog.tts("TTS model cleared, using Android System TTS")
            return
        }
        activeTtsModelId = modelId
        // Model will be loaded on first speak()
        sherpaOnnx?.release()
        DebugLog.tts("TTS model set: $modelId (will load on speak)")
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        currentProfile = profile
        fallbackTts?.language = profile.ttsLocale
    }

    fun getCurrentProfile(): VoiceProfile = currentProfile

    fun isNeuralTtsAvailable(): Boolean = sherpaOnnx != null && activeTtsModelId != null

    /**
     * Speak text using the best available TTS backend.
     * Priority: sherpa-onnx (Kokoro/Piper) > Android System TTS
     */
    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        // Try neural TTS if a model is selected
        if (activeTtsModelId != null && sherpaOnnx != null) {
            if (!sherpaOnnx!!.isReady) {
                loadNeuralModel()
            }
            if (sherpaOnnx!!.isReady) {
                try {
                    sherpaOnnx!!.speak(text)
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "Neural TTS failed, falling back to system TTS", e)
                    DebugLog.tts("Neural TTS failed: ${e.message}, using System TTS")
                }
            }
        }

        // Fallback to Android System TTS
        if (fallbackReady) {
            val utteranceId = "nexus_tts_${System.currentTimeMillis()}"
            completionLatch = java.util.concurrent.CountDownLatch(1)

            fallbackTts?.speak(
                text,
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
            completionLatch?.await(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun loadNeuralModel() {
        val modelId = activeTtsModelId ?: return
        val model = TtsModelInfo.findById(modelId) ?: return
        val modelDir = File(ttsModelsDir, modelId)

        if (!modelDir.exists()) {
            DebugLog.tts("TTS model dir not found: ${modelDir.absolutePath}")
            return
        }

        // Ensure espeak-ng-data is extracted
        val espeakDataDir = ensureEspeakData()

        when (model.engine) {
            TtsModelInfo.TtsEngine.KOKORO -> {
                sherpaOnnx?.loadKokoro(modelDir, espeakDataDir)
            }
            TtsModelInfo.TtsEngine.PIPER -> {
                val onnxFile = modelDir.listFiles()?.find { it.name.endsWith(".onnx") }
                if (onnxFile != null) {
                    sherpaOnnx?.loadPiper(onnxFile, espeakDataDir)
                } else {
                    DebugLog.tts("No .onnx file found in ${modelDir.absolutePath}")
                }
            }
            else -> {
                DebugLog.tts("Unsupported TTS engine: ${model.engine}")
            }
        }
    }

    /**
     * Extract espeak-ng-data from assets to filesDir if not already present.
     * Returns the path to the data directory.
     */
    private fun ensureEspeakData(): String {
        val dataDir = File(context.filesDir, "espeak-ng-data")
        if (dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true) {
            return dataDir.absolutePath
        }

        // Try to extract from assets
        try {
            copyAssetDir("espeak-ng-data", dataDir)
            DebugLog.tts("espeak-ng-data extracted to ${dataDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract espeak-ng-data from assets", e)
            // The data might be bundled with the TTS model download
        }
        return dataDir.absolutePath
    }

    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val assets = context.assets
        val files = assets.list(assetPath) ?: return

        targetDir.mkdirs()
        for (file in files) {
            val subAssetPath = "$assetPath/$file"
            val targetFile = File(targetDir, file)
            val subFiles = assets.list(subAssetPath)

            if (subFiles != null && subFiles.isNotEmpty()) {
                // It's a directory, recurse
                copyAssetDir(subAssetPath, targetFile)
            } else {
                // It's a file, copy
                assets.open(subAssetPath).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun stop() {
        sherpaOnnx?.stop()
        fallbackTts?.stop()
    }

    fun shutdown() {
        sherpaOnnx?.release()
        sherpaOnnx = null
        fallbackTts?.shutdown()
        fallbackTts = null
    }

    fun isAvailable(): Boolean = (sherpaOnnx?.isReady == true) || fallbackReady
}
