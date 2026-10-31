package com.thalzeronlabs.llama

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * A conversation with a loaded model: its own context window (KV cache) and sampler.
 *
 * All native work runs on one dedicated thread, so a session is safe to call from any coroutine.
 * Only one generation runs at a time; a second call waits for the first to finish.
 * Cancel the collecting coroutine to stop a generation mid-way.
 */
class LlamaSession internal constructor(
    private val engine: Engine,
    private val formatChat: (List<ChatMessage>) -> String,
    private val dispatcher: CoroutineDispatcher = newSessionDispatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onClose: (LlamaSession) -> Unit = {},
) : Closeable {

    private val lock = Mutex()
    @Volatile private var closed = false
    @Volatile private var closing = false

    /** Statistics of the last generation that ran to the end (or was cancelled). */
    @Volatile var lastStats: GenerationStats? = null
        private set

    /**
     * Streams the model's continuation of [prompt], token by token.
     *
     * The prompt is appended to what the session already holds, so consecutive calls keep the history.
     * @param maxTokens upper bound on generated tokens.
     * @param addSpecial add the model's begin-of-sequence token (true for the first prompt of a session).
     */
    fun generate(prompt: String, maxTokens: Int = 512, addSpecial: Boolean = true): Flow<String> =
        stream(prompt, maxTokens, addSpecial, resetFirst = false)

    /**
     * Answers the last message of [messages] using the model's own chat template.
     *
     * The whole conversation is re-sent on every call (the session is reset first), which keeps the API stateless:
     * keep your message list, append the user's turn, call [chat], append the answer.
     */
    fun chat(messages: List<ChatMessage>, maxTokens: Int = 512): Flow<String> {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        // The chat template already contains the begin-of-sequence marker.
        return stream(formatChat(messages), maxTokens, addSpecial = false, resetFirst = true)
    }

    private fun stream(prompt: String, maxTokens: Int, addSpecial: Boolean, resetFirst: Boolean): Flow<String> = flow {
        require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }
        lock.withLock {
            check(!closed) { "This session is closed." }
            if (resetFirst) engine.reset()
            val promptTokens = engine.ingest(prompt, addSpecial)
            val decoder = Utf8StreamDecoder()
            val started = clock()
            var generated = 0
            try {
                while (generated < maxTokens && !closing) {
                    currentCoroutineContext().ensureActive()
                    val bytes = engine.nextToken() ?: break
                    generated++
                    val text = decoder.push(bytes)
                    if (text.isNotEmpty()) emit(text)
                }
                val tail = decoder.flush()
                if (tail.isNotEmpty()) emit(tail)
            } finally {
                lastStats = GenerationStats(promptTokens, generated, clock() - started)
            }
        }
    }.flowOn(dispatcher)

    /** Forgets the conversation so far. */
    suspend fun reset() = withContext(dispatcher) { lock.withLock { engine.reset() } }

    /** Tokens currently held in the context window. */
    suspend fun usedTokens(): Int = withContext(dispatcher) { lock.withLock { engine.usedTokens() } }

    /** Size of the context window in tokens. */
    suspend fun contextSize(): Int = withContext(dispatcher) { lock.withLock { engine.contextSize() } }

    /** Stops any running generation at the next token and frees the native memory. Safe to call twice. */
    override fun close() {
        if (closed || closing) return
        closing = true
        runBlocking(dispatcher) {
            lock.withLock {
                closed = true
                engine.close()
            }
        }
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
        onClose(this)
    }

    internal companion object {
        fun newSessionDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "llama-session").apply { isDaemon = true } }
                .asCoroutineDispatcher()
    }
}
