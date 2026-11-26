package com.littlebit.chat.engine

data class ModelInfo(
    val description: String,
    val sizeBytes: Long,
    val fileType: String,
    val gpuLayers: Int = 0,
    val architecture: String = "",
)

data class SamplingParams(
    val temperature: Double = 0.7,
    val topP: Double = 0.90,
    val minP: Double = 0.05,
    val topK: Int = 40,               // 0 = disabled
    val repeatPenalty: Double = 1.1,
    val dryMultiplier: Double = 0.0, // 0 = disabled
    val maxTokens: Int = 1024,
    val thinkingBudget: Int = -1,
    val seed: Long = -1,             // <0 = random
)

data class GenerateResult(
    val status: Int,
    val tokens: Int,
    val tokensPerSec: Double,
    val output: String,
    val error: String? = null,
    val timing: ReplyTiming? = null,
) {
    val isStopped get() = status == STATUS_STOPPED
    val isContextFull get() = status == STATUS_CONTEXT_FULL

    companion object {
        const val STATUS_OK = 0
        const val STATUS_STOPPED = 1
        const val STATUS_CONTEXT_FULL = 2
        const val STATUS_ERROR = -1
    }
}


/** Blocking operations are serialized on the inference dispatcher. stop() is thread-safe. */
interface LlmEngine {
    val isLoaded: Boolean
    fun loadModel(path: String, contextSize: Int, threads: Int, nGpuLayers: Int, batch: Int,
                  flash: Boolean, onProgress: ((Float) -> Unit)? = null): ModelInfo
    fun generate(prompt: String, params: SamplingParams, prefill: (Int, Int) -> Unit = { _, _ -> },
                 onToken: (String) -> Unit = {}): GenerateResult
    fun continueGenerate(params: SamplingParams, onToken: (String) -> Unit): GenerateResult
    fun formatChat(systemPrompt: String, messages: List<Pair<String, String>>, thinking: Boolean = false): String
    fun promptSize(prompt: String): Int
    fun metrics(): DoubleArray
    fun benchmarkContext(): LlmEngine
    fun setThreads(threads: Int)
    fun attachDraft(path: String, threads: Int, progress: (Float) -> Unit)
    fun clearDraft()
    fun reset()
    fun stop()
    fun unload()

    /** Writes the live KV state + token list. Returns the token count, or -1. */
    fun saveKvCache(path: String): Int

    /** Restores a state file into the context. Returns the token count, or -1. */
    fun loadKvCache(path: String): Int
}
