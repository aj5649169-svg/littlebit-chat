package com.littlebit.chat.engine

/**
 * Kotlin wrapper around the native LittleBit engine (libllama_jni.so).
 *
 * The engine runs llama.cpp in-process (patched with LittleBit Q1_0 + Arm
 * KleidiAI, GGML_NATIVE=OFF / GGML_OPENMP=OFF) — the same configuration as
 * the Termux binaries built by scripts/build-android.sh.
 *
 * Threading: [loadModel] and [generate] block the calling thread. Call them
 * from a background dispatcher (e.g. Dispatchers.Default); tokens stream back
 * through [onToken] on that same background thread. Only one of
 * [generate]/[continueGenerate] may run at a time — the ViewModel guards
 * engine access with a Mutex.
 */
class LlamaEngine : LlmEngine {

    fun interface ProgressCallback {
        fun onProgress(progress: Float)
    }

    interface TokenCallback {
        fun onPrefill(done: Int, total: Int)
        fun onToken(piece: String)
        fun onComplete(tokens: Int, tokensPerSec: Double)
        fun onError(message: String)
    }

    @Volatile private var handle: Long = 0

    override val isLoaded: Boolean get() = handle != 0L

    @Volatile
    var isGenerating: Boolean = false
        private set

    init {
        LlamaJni.nativeInit()
    }

    /**
     * Blocks. Loads a GGUF model (any standard quant or LittleBit Q1_0).
     * [nGpuLayers] is plumbed for Vulkan GPU offload (v0.2); keep it 0 on CPU.
     */
    override fun loadModel(
        path: String,
        contextSize: Int,
        threads: Int,
        nGpuLayers: Int,
        batch: Int,
        flash: Boolean,
        onProgress: ((Float) -> Unit)?,
    ): ModelInfo {
        check(handle == 0L) { "A model is already loaded. Unload it first." }
        val h = LlamaJni.nativeLoadModel(path, contextSize, threads, batch, nGpuLayers, flash, onProgress?.let { ProgressCallback(it) })
        check(h != 0L) { "nativeLoadModel returned 0" }
        handle = h
        return ModelInfo(
            description = LlamaJni.nativeModelDesc(h),
            sizeBytes = LlamaJni.nativeModelSizeBytes(h),
            fileType = LlamaJni.nativeModelFtype(h),
            gpuLayers = LlamaJni.nativeModelGpuLayers(h),
            architecture = LlamaJni.nativeArchitecture(h),
        )
    }

    override fun metrics() = LlamaJni.nativeMetrics(handle)
    override fun benchmarkContext() = LlamaEngine().also {
        it.handle = LlamaJni.nativeBenchmarkContext(handle)
        check(it.isLoaded) { "Could not allocate a benchmark context" }
    }
    override fun reset() = LlamaJni.nativeReset(handle)
    override fun promptSize(prompt: String) = LlamaJni.nativePromptSize(handle, prompt)

    override fun setThreads(threads: Int) {
        if (handle != 0L) LlamaJni.nativeSetThreads(handle, threads)
    }

    private var draft: LlamaEngine? = null

    override fun clearDraft() {
        if (handle != 0L) LlamaJni.nativeAttachDraft(handle, 0)
        draft?.unload()
        draft = null
    }

    override fun attachDraft(path: String, threads: Int, progress: (Float) -> Unit) {
        clearDraft()
        val candidate = LlamaEngine()
        try {
            candidate.loadModel(path, metrics()[3].toInt(), threads, 0, 128, false, progress)
            check(LlamaJni.nativeAttachDraft(handle, candidate.handle)) { "Incompatible vocabulary or attention architecture" }
            draft = candidate
        } finally {
            if (draft == null) candidate.unload()
        }
    }

    @Synchronized override fun unload() {
        val h = handle
        if (h != 0L) {
            clearDraft()
            LlamaJni.nativeUnload(h)
            handle = 0L
        }
    }

    /** Signal the native loop to stop at the next token. Safe to call anytime. */
    @Synchronized override fun stop() {
        if (handle != 0L) LlamaJni.nativeStop(handle)
    }

    /** Formats the conversation with the model's own GGUF chat template. */
    override fun formatChat(systemPrompt: String, messages: List<Pair<String, String>>, thinking: Boolean): String {
        check(handle != 0L) { "No model loaded" }
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        return LlamaJni.nativeFormatChat(handle, systemPrompt, roles, contents, thinking)
    }

    /** Blocking generation loop. Call from a background thread. */
    override fun generate(
        prompt: String,
        params: SamplingParams,
        prefill: (Int, Int) -> Unit,
        onToken: (String) -> Unit,
    ): GenerateResult = runGeneration(prompt, params, onToken, prefill)

    /**
     * Continue generation from the current KV-cache state (no prompt feeding).
     * Used after flushing a speculative pre-warm draft; falls back to
     * [generate] if the native side reports an error.
     */
    override fun continueGenerate(
        params: SamplingParams,
        onToken: (String) -> Unit,
    ): GenerateResult = runGeneration(null, params, onToken)

    private fun runGeneration(
        prompt: String?,
        params: SamplingParams,
        onToken: (String) -> Unit,
        prefill: (Int, Int) -> Unit = { _, _ -> },
    ): GenerateResult {
        check(handle != 0L) { "No model loaded" }
        isGenerating = true
        return try {
            var error: String? = null
            val output = StringBuilder()
            var tokens = 0
            var tps = 0.0
            val cb = object : TokenCallback {
                override fun onPrefill(done: Int, total: Int) = prefill(done, total)
                override fun onToken(piece: String) {
                    output.append(piece)
                    tokens++
                    onToken(piece)
                }

                override fun onComplete(t: Int, rate: Double) {
                    tokens = t
                    tps = rate
                }

                override fun onError(message: String) {
                    error = message
                }
            }
            val status = if (prompt == null) {
                LlamaJni.nativeContinueGenerate(
                    handle,
                    params.temperature, params.topK, params.topP, params.minP, params.repeatPenalty, params.dryMultiplier,
                    params.maxTokens, params.seed, cb,
                )
            } else {
                LlamaJni.nativeGenerate(
                    handle, prompt,
                    params.temperature, params.topK, params.topP, params.minP, params.repeatPenalty, params.dryMultiplier,
                    params.maxTokens, params.seed, cb,
                )
            }
            val m = metrics()
            val timing = ReplyTiming(m[5].toInt(), m[6], tokens, m[7], m[0], m[2].toInt(), m[3].toInt())
            GenerateResult(status, tokens, tps, output.toString(), error, timing)
        } finally {
            isGenerating = false
        }
    }
}
