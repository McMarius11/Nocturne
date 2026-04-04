package com.nexus.companion.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Volatile
    private var backendsLoaded = false

    /** Dedicated scope for engine operations (survives ViewModel lifecycle) */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentModel: ModelInfo? = null

    /** Emits each token as it's generated — observe for streaming UI */
    private val _tokenStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val tokenStream: SharedFlow<String> = _tokenStream

    /** Emits complete sentences for TTS streaming (sentence boundary detection) */
    private val _sentenceStream = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val sentenceStream: SharedFlow<String> = _sentenceStream

    /** KV cache state: true if we can use continueStreaming for next turn */
    @Volatile
    private var isKvCached = false

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
            // Load dynamic CPU backend variants (once)
            if (!backendsLoaded) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                DebugLog.llm("Loading backends from: $nativeLibDir")
                jni.loadBackends(nativeLibDir)
                backendsLoaded = true
            }

            val loadStart = System.currentTimeMillis()
            val success = jni.loadModel(
                modelPath = path,
                nThreads = 4,
                contextLength = contextLength
            )
            val loadMs = System.currentTimeMillis() - loadStart
            if (success) {
                currentModel = model
                isKvCached = false
                triedSingleThread = false
                val info = jni.getModelInfo()
                DebugLog.llm("Model loaded in ${loadMs}ms ($info)")

                // Log GGML backend info (CPU, Vulkan, etc.)
                try {
                    val backendInfo = jni.getBackendInfo()
                    DebugLog.llm("GGML: $backendInfo")
                } catch (e: Exception) {
                    DebugLog.llm("Backend info error: ${e.message}")
                }

                // Smoke test: minimal decode to verify llama_decode works
                DebugLog.llm("Running smoke test (decode 'hello')...")
                val smokeStart = System.currentTimeMillis()
                try {
                    val smokeResult = jni.benchmarkDecode()
                    val smokeMs = System.currentTimeMillis() - smokeStart
                    DebugLog.llm("Smoke test (${smokeMs}ms): $smokeResult")

                    if (smokeResult.startsWith("ERROR")) {
                        DebugLog.llm("SMOKE TEST FAILED — inference will not work!")
                    }
                } catch (e: Exception) {
                    val smokeMs = System.currentTimeMillis() - smokeStart
                    DebugLog.llm("Smoke test CRASHED after ${smokeMs}ms: ${e.message}")
                }
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

    /** Track whether we already tried falling back to 1 thread */
    @Volatile
    private var triedSingleThread = false

    /**
     * Generate a response with streaming tokens.
     * Each token is emitted to [tokenStream] as it's generated.
     * If generation times out with 0 tokens, automatically retries with 1 thread.
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

        val result = generateInternal(systemPrompt, chatHistory, userMessage, memoryContext, maxTokens)

        // Thread fallback: if generation produced nothing and we haven't tried 1 thread yet
        if (result.isBlank() && !triedSingleThread) {
            DebugLog.llm("=== THREAD FALLBACK: Retrying with 1 thread ===")
            try {
                val ok = jni.setThreadCount(1)
                if (ok) {
                    triedSingleThread = true
                    DebugLog.llm("Context recreated with 1 thread, retrying generation...")
                    return@withContext generateInternal(systemPrompt, chatHistory, userMessage, memoryContext, maxTokens)
                } else {
                    DebugLog.llm("Failed to set thread count to 1")
                }
            } catch (e: Exception) {
                DebugLog.llm("Thread fallback error: ${e.message}")
            }
        }

        result
    }

    private suspend fun generateInternal(
        systemPrompt: String,
        chatHistory: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String,
        maxTokens: Int
    ): String {
        try {
            val format = currentModel?.promptFormat ?: PromptFormat.ALPACA
            val useIncremental = isKvCached && jni.getCurrentPosition() > 0

            val genStart = System.currentTimeMillis()
            var tokenCount = 0
            val sentenceBuffer = StringBuilder()

            // Callback: streaming tokens + sentence detection for TTS
            val callback = object : LlamaJni.TokenCallback {
                override fun onToken(token: String) {
                    tokenCount++
                    _tokenStream.tryEmit(token)

                    if (tokenCount == 1) {
                        val firstTokenMs = System.currentTimeMillis() - genStart
                        DebugLog.llm("First token in ${firstTokenMs}ms: \"${token.take(20)}\"")
                    }

                    // Sentence-level TTS streaming: detect sentence boundaries
                    sentenceBuffer.append(token)
                    val text = sentenceBuffer.toString()
                    val sentenceEnd = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                    if (sentenceEnd >= 0 && text.length > sentenceEnd + 1) {
                        // We have a complete sentence + start of next
                        val sentence = text.substring(0, sentenceEnd + 1).trim()
                        if (sentence.length >= 3) { // Skip tiny fragments
                            _sentenceStream.tryEmit(sentence)
                        }
                        sentenceBuffer.clear()
                        sentenceBuffer.append(text.substring(sentenceEnd + 1))
                    }
                }

                override fun onProgress(message: String) {
                    DebugLog.llm("JNI: $message")
                }
            }

            val result: String

            if (useIncremental) {
                // Incremental mode: only decode new user turn
                val continuation = buildContinuation(format, userMessage)
                DebugLog.llm("Continue: ${continuation.length} chars (KV pos=${jni.getCurrentPosition()})")
                DebugLog.llm("Continuation: ${continuation.replace("\n", "\\n")}")

                val watchdogJob = launchWatchdog(genStart) { tokenCount }
                result = try {
                    jni.continueStreaming(continuation, maxTokens, callback)
                } finally {
                    watchdogJob.cancel()
                }
            } else {
                // Full prompt mode: decode everything from scratch
                val prompt = buildPrompt(format, systemPrompt, chatHistory, userMessage, memoryContext)
                DebugLog.llm("Generate: ${prompt.length} chars, format=$format, maxTokens=$maxTokens")
                DebugLog.llm("Prompt first 120: ${prompt.take(120).replace("\n", "\\n")}")
                DebugLog.llm("Prompt last 120: ${prompt.takeLast(120).replace("\n", "\\n")}")
                DebugLog.llm("Model state: ${jni.getModelInfo()}")

                val watchdogJob = launchWatchdog(genStart) { tokenCount }
                result = try {
                    jni.generateStreaming(prompt, maxTokens, callback)
                } finally {
                    watchdogJob.cancel()
                }
            }

            // Emit remaining sentence buffer for TTS
            val remaining = sentenceBuffer.toString().trim()
            if (remaining.length >= 2) {
                _sentenceStream.tryEmit(remaining)
            }

            val genMs = System.currentTimeMillis() - genStart
            val tokPerSec = if (genMs > 0) tokenCount * 1000.0 / genMs else 0.0

            if (result.startsWith("[Error:")) {
                val nativeError = jni.getLastError()
                DebugLog.llm("Generation error: $result | Native: $nativeError")
                isKvCached = false // Invalidate cache on error
            } else if (result.isBlank()) {
                val nativeError = jni.getLastError()
                DebugLog.llm("WARNING: Empty response after ${genMs}ms ($tokenCount tokens)")
                DebugLog.llm("Native error: $nativeError")
                isKvCached = false
            } else {
                DebugLog.llm("Response: ${result.length} chars, $tokenCount tokens in ${genMs}ms (${String.format("%.1f", tokPerSec)} t/s)")
                isKvCached = true // Cache is warm for next turn
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation", e)
            DebugLog.llm("Generation exception: ${e.message}")
            isKvCached = false
            return "[Error: ${e.message ?: "Unknown"}]"
        }
    }

    /** Launch watchdog coroutine that logs progress and aborts after timeout */
    private fun launchWatchdog(startMs: Long, tokenCount: () -> Int): Job {
        return engineScope.launch {
            var elapsed = 0
            while (true) {
                kotlinx.coroutines.delay(15_000)
                elapsed += 15
                val tc = tokenCount()
                if (tc == 0) {
                    DebugLog.llm("Prompt decode in progress... ${elapsed}s")
                } else {
                    DebugLog.llm("Generating... ${elapsed}s ($tc tokens)")
                }
                if (elapsed >= 300 && tc == 0) {
                    DebugLog.llm("TIMEOUT: Aborting — no tokens after ${elapsed}s")
                    jni.abort()
                    break
                }
            }
        }
    }

    /**
     * Build continuation text for incremental KV cache mode.
     * Only includes the new user turn — previous conversation is already in the cache.
     */
    private fun buildContinuation(format: PromptFormat, userMessage: String): String {
        return when (format) {
            PromptFormat.CHATML ->
                "<|im_end|>\n<|im_start|>user\n$userMessage<|im_end|>\n<|im_start|>assistant\n"
            PromptFormat.GEMMA ->
                "<end_of_turn>\n<start_of_turn>user\n$userMessage<end_of_turn>\n<start_of_turn>model\n"
            PromptFormat.ALPACA ->
                "\n\n### Input:\n$userMessage\n\n### Response:\n"
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
        isKvCached = false
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
