package com.nexus.companion.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlmEngine"

        // Singleton — the JNI layer uses global statics, so only one
        // LlmEngine should exist. PhoneCallService reuses this instance.
        @Volatile
        private var INSTANCE: LlmEngine? = null

        fun getInstance(context: Context): LlmEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val jni = LlamaJni()
    private val modelManager = ModelManager(context)

    private var currentModel: ModelInfo? = null

    val downloadState get() = modelManager.downloadProgress

    fun isReady(): Boolean = jni.isModelLoaded()

    fun getCurrentModel(): ModelInfo? = currentModel

    fun getModelManager(): ModelManager = modelManager

    suspend fun loadModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            // Download if not present (via ForegroundService with WakeLock)
            if (!modelManager.isModelDownloaded(model)) {
                val started = modelManager.startDownload(model)
                if (!started) return@withContext false

                // Wait for download to complete
                modelManager.downloadProgress.first { state ->
                    state is ModelManager.DownloadState.Idle || state is ModelManager.DownloadState.Error
                }

                if (!modelManager.isModelDownloaded(model)) return@withContext false
            }

            // Unload previous
            try {
                if (jni.isModelLoaded()) {
                    jni.unloadModel()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unloading previous model", e)
            }

            val path = modelManager.getModelPath(model).absolutePath
            // Gemma 3 / Qwen3 support 8192 context, smaller models use 4096
            val contextLength = if (model.sizeBytes > 2_000_000_000L) 8192 else 4096
            DebugLog.llm("Loading ${model.displayName} (${model.sizeGb} GB, ctx=$contextLength)")
            val loadStart = System.currentTimeMillis()
            val success = jni.loadModel(
                modelPath = path,
                nThreads = 4,  // Tensor G4 optimized
                contextLength = contextLength
            )
            val loadMs = System.currentTimeMillis() - loadStart
            if (success) {
                currentModel = model
                DebugLog.llm("Model loaded in ${loadMs}ms")
            } else {
                DebugLog.llm("Model load FAILED after ${loadMs}ms")
            }
            success
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not available", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            false
        }
    }

    suspend fun generate(
        systemPrompt: String,
        chatHistory: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String = "",
        maxTokens: Int = 512
    ): String = withContext(Dispatchers.IO) {
        if (!jni.isModelLoaded()) return@withContext "[Model not loaded]"

        try {
            val format = currentModel?.promptFormat ?: PromptFormat.ALPACA
            val prompt = buildPrompt(format, systemPrompt, chatHistory, userMessage, memoryContext)
            DebugLog.llm("Generate: ${prompt.length} chars, format=$format, maxTokens=$maxTokens")
            val genStart = System.currentTimeMillis()
            val result = jni.generate(prompt, maxTokens)
            val genMs = System.currentTimeMillis() - genStart
            DebugLog.llm("Response: ${result.length} chars in ${genMs}ms")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation", e)
            throw e
        }
    }

    private fun buildPrompt(
        format: PromptFormat,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String = when (format) {
        PromptFormat.ALPACA -> buildAlpacaPrompt(systemPrompt, history, userMessage, memoryContext)
        PromptFormat.GEMMA2 -> buildGemma2Prompt(systemPrompt, history, userMessage, memoryContext)
        PromptFormat.GEMMA3 -> buildGemma3Prompt(systemPrompt, history, userMessage, memoryContext)
        PromptFormat.CHATML -> buildChatMlPrompt(systemPrompt, history, userMessage, memoryContext)
    }

    private fun buildSystemBlock(systemPrompt: String, memoryContext: String): String {
        val sb = StringBuilder(systemPrompt)
        if (memoryContext.isNotBlank()) {
            sb.append("\n\nMemories about the user:\n")
            sb.append(memoryContext)
        }
        return sb.toString()
    }

    private fun buildAlpacaPrompt(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String {
        val sb = StringBuilder()
        sb.append("### Instruction:\n")
        sb.append(buildSystemBlock(systemPrompt, memoryContext))
        sb.append("\n\n")
        for ((user, assistant) in history.takeLast(10)) {
            sb.append("### Input:\n$user\n\n### Response:\n$assistant\n\n")
        }
        sb.append("### Input:\n$userMessage\n\n### Response:\n")
        return sb.toString()
    }

    private fun buildGemma2Prompt(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String {
        val sb = StringBuilder()
        val system = buildSystemBlock(systemPrompt, memoryContext)
        // Gemma 2 has no system turn — inject system prompt into first user turn
        for ((user, assistant) in history.takeLast(10)) {
            sb.append("<start_of_turn>user\n$user<end_of_turn>\n")
            sb.append("<start_of_turn>model\n$assistant<end_of_turn>\n")
        }
        sb.append("<start_of_turn>user\n$system\n\n$userMessage<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildGemma3Prompt(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String {
        val sb = StringBuilder()
        val system = buildSystemBlock(systemPrompt, memoryContext)
        // Gemma 3 supports a dedicated system turn
        sb.append("<start_of_turn>system\n$system<end_of_turn>\n")
        for ((user, assistant) in history.takeLast(10)) {
            sb.append("<start_of_turn>user\n$user<end_of_turn>\n")
            sb.append("<start_of_turn>model\n$assistant<end_of_turn>\n")
        }
        sb.append("<start_of_turn>user\n$userMessage<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildChatMlPrompt(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(buildSystemBlock(systemPrompt, memoryContext))
        sb.append("<|im_end|>\n")
        for ((user, assistant) in history.takeLast(10)) {
            sb.append("<|im_start|>user\n$user<|im_end|>\n")
            sb.append("<|im_start|>assistant\n$assistant<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n$userMessage<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    fun abort() {
        jni.abort()
    }

    /**
     * Unload model from RAM. Aborts any in-flight generation first,
     * then unloads on IO thread to prevent ANR on main thread.
     */
    fun unload() {
        DebugLog.llm("Unloading model (abort + free)")
        jni.abort() // Signal generation to stop (non-blocking)
        currentModel = null
        // Unload on IO thread — JNI mutex would block main thread
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                jni.unloadModel()
            } catch (e: Exception) {
                Log.w(TAG, "Error during unload", e)
            }
        }
    }
}
