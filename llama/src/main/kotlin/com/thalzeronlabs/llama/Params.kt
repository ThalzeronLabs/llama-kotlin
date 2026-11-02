package com.thalzeronlabs.llama

/** How the GGUF file is loaded. */
data class ModelParams(
    /** Memory-map the file instead of reading it into RAM: faster start, lower peak memory. */
    val useMmap: Boolean = true,
    /**
     * Longest text, in tokens, that [LlamaModel.embed] accepts. Capped at the context the model was trained with.
     * The embedding context is created on the first [LlamaModel.embed] call, not at load time.
     */
    val embeddingContextSize: Int = 512,
    /** CPU threads for [LlamaModel.embed]; 0 picks a safe default for the device (cores - 2, at most 4). */
    val embeddingThreads: Int = 0,
) {
    init {
        require(embeddingContextSize >= 8) { "embeddingContextSize must be at least 8, was $embeddingContextSize" }
        require(embeddingThreads >= 0) { "embeddingThreads must be 0 (auto) or positive, was $embeddingThreads" }
    }
}

/**
 * Context size, threading and sampling for one [LlamaSession].
 *
 * The sampler is rebuilt from these values at the start of every generation, so with the same [seed], the same
 * session content and the same prompt, the model gives the same answer every time (on the same device and build).
 */
data class SessionParams(
    /** Context window in tokens (prompt + answer + history). */
    val contextSize: Int = 2048,
    /** How many prompt tokens are processed per step. */
    val batchSize: Int = 512,
    /** CPU threads; 0 picks a safe default for the device (cores - 2, at most 4). */
    val threads: Int = 0,
    /** 0 means greedy (always the most likely token); higher is more creative. */
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    /** Seed of the random sampler. Fix it to get reproducible answers; ignored when [temperature] is 0. */
    val seed: Int = 42,
) {
    init {
        require(contextSize >= 128) { "contextSize must be at least 128, was $contextSize" }
        require(batchSize in 1..contextSize) { "batchSize must be in 1..contextSize, was $batchSize" }
        require(threads >= 0) { "threads must be 0 (auto) or positive, was $threads" }
        require(temperature >= 0f) { "temperature must not be negative, was $temperature" }
        require(topK >= 0) { "topK must not be negative, was $topK" }
        require(topP in 0f..1f) { "topP must be in 0..1, was $topP" }
        require(minP in 0f..1f) { "minP must be in 0..1, was $minP" }
    }
}

/** One turn of a conversation. */
data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wireName: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

    companion object {
        fun system(content: String) = ChatMessage(Role.SYSTEM, content)
        fun user(content: String) = ChatMessage(Role.USER, content)
        fun assistant(content: String) = ChatMessage(Role.ASSISTANT, content)
    }
}

/** Why a generation ended. */
enum class StopReason {
    /** The model produced its end-of-generation token (or the grammar was complete). */
    END_OF_GENERATION,

    /** `maxTokens` tokens were generated. */
    MAX_TOKENS,

    /** One of the stop sequences appeared in the output. */
    STOP_SEQUENCE,

    /** The context window filled up. */
    CONTEXT_FULL,

    /** The collector stopped early, the session was closed, or the generation failed. */
    CANCELLED,
}

/** Numbers from the last finished generation. All times come from the session's monotonic clock. */
data class GenerationStats(
    /** Tokens in the prompt, including the ones reused from the cache ([cachedPromptTokens]). */
    val promptTokens: Int,
    val generatedTokens: Int,
    /** Time spent generating, from the end of prompt processing to the last token. */
    val generationMillis: Long,
    /** Time spent processing the prompt (only the part that was not already cached). */
    val promptMillis: Long = 0,
    /** Time from the call to the first generated token (prompt processing included); null if none was generated. */
    val timeToFirstTokenMillis: Long? = null,
    /** Prompt tokens that were already in the KV cache and did not need processing (see [LlamaSession.chat]). */
    val cachedPromptTokens: Int = 0,
    val stopReason: StopReason = StopReason.END_OF_GENERATION,
) {
    /** Generation speed in tokens per second. */
    val tokensPerSecond: Double
        get() = if (generationMillis <= 0) 0.0 else generatedTokens * 1000.0 / generationMillis

    /** Prompt processing speed in tokens per second, counting only the tokens that were actually processed. */
    val promptTokensPerSecond: Double
        get() = if (promptMillis <= 0) 0.0 else (promptTokens - cachedPromptTokens) * 1000.0 / promptMillis
}

/** Thrown when a prompt does not fit in what is left of the context window. Call [LlamaSession.reset] or use a bigger context. */
class ContextFullException(message: String) : IllegalStateException(message)
