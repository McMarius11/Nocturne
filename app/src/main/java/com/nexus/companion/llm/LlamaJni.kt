package com.nexus.companion.llm

class LlamaJni {
    companion object {
        init {
            System.loadLibrary("nexus-llama")
        }
    }

    /** Callback interface for streaming token generation */
    interface TokenCallback {
        fun onToken(token: String)
        fun onProgress(message: String)
    }

    external fun loadModel(modelPath: String, nThreads: Int, contextLength: Int): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun generateStreaming(prompt: String, maxTokens: Int, callback: TokenCallback): String
    external fun abort()
    external fun unloadModel()
    external fun isModelLoaded(): Boolean
    external fun getLastError(): String
    external fun getModelInfo(): String
}
