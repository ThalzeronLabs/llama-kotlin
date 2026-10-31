package com.thalzeronlabs.llama

/** How the GGUF file is loaded. */
data class ModelParams(
    /** Memory-map the file instead of reading it into RAM: faster start, lower peak memory. */
    val useMmap: Boolean = true,
)

/** Context size, threading and sampling for one [LlamaSession]. */
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

/** Numbers from the last finished generation. */
data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val generationMillis: Long,
) {
    val tokensPerSecond: Double
        get() = if (generationMillis <= 0) 0.0 else generatedTokens * 1000.0 / generationMillis
}

/** Thrown when a prompt does not fit in what is left of the context window. Call [LlamaSession.reset] or use a bigger context. */
class ContextFullException(message: String) : IllegalStateException(message)
