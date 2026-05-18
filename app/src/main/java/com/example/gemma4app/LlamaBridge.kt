package com.example.gemma4app

object LlamaBridge {
    init {
        System.loadLibrary("llama_bridge")
    }

    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int, nBatch: Int): Boolean
    external fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ): String

    external fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: StreamingCallback
    )
    external fun stopGeneration()
    external fun freeModel()

    interface StreamingCallback {
        fun onToken(token: String)
    }
}