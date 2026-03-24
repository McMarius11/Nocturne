package com.nexus.companion.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmEngine(private val context: Context) {

    private val jni = LlamaJni()
    private val modelManager = ModelManager(context)

    private var currentModel: ModelInfo? = null

    val downloadState get() = modelManager.downloadProgress

    fun isReady(): Boolean = jni.isModelLoaded()

    fun getCurrentModel(): ModelInfo? = currentModel

    fun getModelManager(): ModelManager = modelManager

    suspend fun loadModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        // Download if not present
        if (!modelManager.isModelDownloaded(model)) {
            val downloaded = modelManager.downloadModel(model)
            if (!downloaded) return@withContext false
        }

        // Unload previous
        if (jni.isModelLoaded()) {
            jni.unloadModel()
        }

        val path = modelManager.getModelPath(model).absolutePath
        val success = jni.loadModel(
            modelPath = path,
            nThreads = 4,  // Tensor G4 optimized
            contextLength = 4096
        )
        if (success) {
            currentModel = model
        }
        success
    }

    suspend fun generate(
        systemPrompt: String,
        chatHistory: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String = "",
        maxTokens: Int = 512
    ): String = withContext(Dispatchers.IO) {
        if (!jni.isModelLoaded()) return@withContext "[Modell nicht geladen]"

        val prompt = buildPrompt(systemPrompt, chatHistory, userMessage, memoryContext)
        jni.generate(prompt, maxTokens)
    }

    private fun buildPrompt(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        memoryContext: String
    ): String {
        val sb = StringBuilder()

        // Alpaca-style prompt format (works with Noromaid, MythoMax, Gemma)
        sb.append("### Instruction:\n")
        sb.append(systemPrompt)
        if (memoryContext.isNotBlank()) {
            sb.append("\n\nErinnerungen über den Nutzer:\n")
            sb.append(memoryContext)
        }
        sb.append("\n\n")

        // Chat history
        for ((user, assistant) in history.takeLast(10)) {
            sb.append("### Input:\n$user\n\n### Response:\n$assistant\n\n")
        }

        // Current message
        sb.append("### Input:\n$userMessage\n\n### Response:\n")

        return sb.toString()
    }

    fun abort() {
        jni.abort()
    }

    fun unload() {
        jni.unloadModel()
        currentModel = null
    }
}
