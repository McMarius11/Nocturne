package com.nexus.companion.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlmEngine"

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

    /** Dedicated scope for engine operations (survives ViewModel lifecycle) */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentModel: ModelInfo? = null

    /** Emits each token as it's generated — observe for streaming UI */
    private val _tokenStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val tokenStream: SharedFlow<String> = _tokenStream

    val downloadState get() = modelManager.downloadProgress

    fun isReady(): Boolean = jni.isModelLoaded()

    fun getCurrentModel(): ModelInfo? = currentModel

    fun getModelManager(): ModelManager = modelManager

    suspend fun loadModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!modelManager.isModelDownloaded(model)) {
                DebugLog.llm("Model not downloaded: ${model.id}")
                return@withContext false
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
            val fileSize = java.io.File(path).length() / 1_000_000
            val contextLength = model.contextLength
            DebugLog.llm("Loading ${model.displayName} (${fileSize} MB, ctx=$contextLength, format=${model.promptFormat})")
            DebugLog.llm("Path: $path")
            val loadStart = System.currentTimeMillis()
            val success = jni.loadModel(
                modelPath = path,
                nThreads = 4,
                contextLength = contextLength
            )
            val loadMs = System.currentTimeMillis() - loadStart
            if (success) {
                currentModel = model
                val info = jni.getModelInfo()
                DebugLog.llm("Model loaded in ${loadMs}ms ($info)")
            } else {
                val error = jni.getLastError()
                DebugLog.llm("Model load FAILED after ${loadMs}ms: $error")
            }
            success
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not available", e)
            DebugLog.llm("FATAL: Native library not found")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            DebugLog.llm("Load error: ${e.message}")
            false
        }
    }

    /**
     * Generate a response with streaming tokens.
     * Each token is emitted to [tokenStream] as it's generated.
     */
    suspend fun generate(
        systemPrompt: String,
        chatHistory: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String = "",
        maxTokens: Int = 512
    ): String = withContext(Dispatchers.IO) {
        if (!jni.isModelLoaded()) {
            DebugLog.llm("Generate called but model not loaded!")
            return@withContext "[Model not loaded]"
        }

        try {
            val format = currentModel?.promptFormat ?: PromptFormat.ALPACA
            val prompt = buildPrompt(format, systemPrompt, chatHistory, userMessage, memoryContext)
            DebugLog.llm("Generate: ${prompt.length} chars, format=$format, maxTokens=$maxTokens")
            DebugLog.llm("Prompt first 120: ${prompt.take(120).replace("\n", "\\n")}")
            DebugLog.llm("Prompt last 120: ${prompt.takeLast(120).replace("\n", "\\n")}")

            val genStart = System.currentTimeMillis()
            var tokenCount = 0

            // Use streaming generation with token counting
            val callback = object : LlamaJni.TokenCallback {
                override fun onToken(token: String) {
                    tokenCount++
                    _tokenStream.tryEmit(token)
                    // Log first token timing
                    if (tokenCount == 1) {
                        val firstTokenMs = System.currentTimeMillis() - genStart
                        DebugLog.llm("First token in ${firstTokenMs}ms: \"${token.take(20)}\"")
                    }
                }
            }

            DebugLog.llm("Model state before generate: ${jni.getModelInfo()}")
            DebugLog.llm("Calling JNI generateStreaming...")

            // Watchdog: log every 10s while JNI is running so we know it's alive vs hung
            val watchdogJob = engineScope.launch {
                var elapsed = 10
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    DebugLog.llm("Still generating... ${elapsed}s ($tokenCount tokens so far)")
                    elapsed += 10
                    // Hard timeout: abort after 90s with no tokens (prompt decode hung)
                    if (elapsed >= 90 && tokenCount == 0) {
                        DebugLog.llm("TIMEOUT: Aborting generation — no tokens after ${elapsed}s")
                        DebugLog.llm("This usually means the model is too slow for this device or has a compatibility issue.")
                        jni.abort()
                        break
                    }
                }
            }

            val result = try {
                jni.generateStreaming(prompt, maxTokens, callback)
            } finally {
                watchdogJob.cancel()
            }
            val genMs = System.currentTimeMillis() - genStart
            val tokPerSec = if (genMs > 0) tokenCount * 1000.0 / genMs else 0.0

            if (result.startsWith("[Error:")) {
                val nativeError = jni.getLastError()
                DebugLog.llm("Generation error: $result")
                DebugLog.llm("Native error: $nativeError")
                DebugLog.llm("Model info: ${jni.getModelInfo()}")
            } else if (result.isBlank()) {
                val nativeError = jni.getLastError()
                DebugLog.llm("WARNING: Empty response after ${genMs}ms ($tokenCount tokens streamed)")
                DebugLog.llm("Native error: $nativeError")
                DebugLog.llm("Model info: ${jni.getModelInfo()}")
            } else {
                DebugLog.llm("Response: ${result.length} chars, $tokenCount tokens in ${genMs}ms (${String.format("%.1f", tokPerSec)} t/s)")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation", e)
            DebugLog.llm("Generation exception: ${e.message}")
            "[Error: ${e.message ?: "Unknown"}]"
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
        PromptFormat.GEMMA -> buildGemmaPrompt(systemPrompt, history, userMessage, memoryContext)
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

    /**
     * Gemma 3/4 prompt format.
     * Uses <bos> + <start_of_turn>/<end_of_turn> with system, user, model roles.
     * <bos> is required — without it, Gemma produces empty/garbage output.
     * The JNI tokenizer has parse_special=true, so <bos> is parsed as special token ID 2.
     */
    private fun buildGemmaPrompt(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String {
        val sb = StringBuilder()
        val system = buildSystemBlock(systemPrompt, memoryContext)
        sb.append("<bos><start_of_turn>system\n$system<end_of_turn>\n")
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

    fun unload() {
        DebugLog.llm("Unloading model (abort + free)")
        jni.abort()
        currentModel = null
        // Use engine scope (not GlobalScope) — must complete even if ViewModel is cleared
        engineScope.launch {
            try {
                jni.unloadModel()
            } catch (e: Exception) {
                Log.w(TAG, "Error during unload", e)
            }
        }
    }
}
