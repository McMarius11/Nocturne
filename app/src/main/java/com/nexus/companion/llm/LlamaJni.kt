package com.nexus.companion.llm

class LlamaJni {
    companion object {
        init {
            System.loadLibrary("nexus-llama")
        }
    }

    external fun loadModel(modelPath: String, nThreads: Int, contextLength: Int): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun abort()
    external fun unloadModel()
    external fun isModelLoaded(): Boolean
}
