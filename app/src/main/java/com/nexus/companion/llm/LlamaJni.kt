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

    /** Load CPU backend variants from native lib dir. Must be called once before loadModel(). */
    external fun loadBackends(nativeLibDir: String)

    external fun loadModel(modelPath: String, nThreads: Int, contextLength: Int): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun generateStreaming(prompt: String, maxTokens: Int, callback: TokenCallback): String

    /** Continue generation without resetting KV cache — for multi-turn conversations */
    external fun continueStreaming(newText: String, maxTokens: Int, callback: TokenCallback): String

    /** Get current KV cache position (0 = empty) */
    external fun getCurrentPosition(): Int

    external fun abort()
    external fun unloadModel()
    external fun isModelLoaded(): Boolean
    external fun getLastError(): String
    external fun getModelInfo(): String

    /** Smoke test: tokenize "hello", decode 1 batch, sample 1 token. Returns timing info or error. */
    external fun benchmarkDecode(): String

    /** Returns registered GGML backends and devices (CPU, Vulkan, etc.) */
    external fun getBackendInfo(): String

    /** Recreate context with a different thread count (for fallback from N→1 threads) */
    external fun setThreadCount(nThreads: Int): Boolean
}
