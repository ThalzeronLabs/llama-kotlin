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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
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
     * @param grammar restricts the output to a grammar, for example [Grammar.jsonSchema]; null means free text.
     * @param stop ends the generation as soon as the output contains one of these strings. The stop sequence itself
     *   is not emitted.
     * @param temperature overrides [SessionParams.temperature] for this call only (0 = greedy); null keeps the
     *   session's value. The KV cache is kept, so changing it costs nothing.
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        addSpecial: Boolean = true,
        grammar: Grammar? = null,
        stop: List<String> = emptyList(),
        temperature: Float? = null,
    ): Flow<String> = stream(maxTokens, grammar, stop, temperature) {
        engine.ingest(prompt, addSpecial).let { PromptIngest(it, 0) }
    }

    /**
     * Answers the last message of [messages] using the model's own chat template.
     *
     * Pass the whole conversation on every call: keep your message list, append the user's turn, call [chat],
     * append the answer. The session keeps its KV cache between calls and only processes the part of the
     * conversation it has not seen yet (see [GenerationStats.cachedPromptTokens]), so later turns start fast.
     * A conversation that diverges from what the session holds is handled too: the cache is cut where they differ.
     *
     * @param grammar restricts the output to a grammar, for example [Grammar.jsonSchema]; null means free text.
     * @param stop ends the generation as soon as the output contains one of these strings (not emitted).
     * @param temperature overrides [SessionParams.temperature] for this answer only (0 = greedy); null keeps the
     *   session's value. Use it for a "creativity" setting instead of opening a new session: the cache is kept.
     */
    fun chat(
        messages: List<ChatMessage>,
        maxTokens: Int = 512,
        grammar: Grammar? = null,
        stop: List<String> = emptyList(),
        temperature: Float? = null,
    ): Flow<String> {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val prompt = formatChat(messages)
        // The chat template already contains the begin-of-sequence marker.
        return stream(maxTokens, grammar, stop, temperature) { engine.ingestReusingPrefix(prompt, addSpecial = false) }
    }

    private fun stream(
        maxTokens: Int,
        grammar: Grammar?,
        stop: List<String>,
        temperature: Float?,
        ingestPrompt: () -> PromptIngest,
    ): Flow<String> {
        require(temperature == null || (temperature >= 0f && temperature.isFinite())) {
            "temperature must be finite and not negative, was $temperature"
        }
        return generation(maxTokens, grammar, stop, temperature, ingestPrompt)
    }

    private fun generation(
        maxTokens: Int,
        grammar: Grammar?,
        stop: List<String>,
        temperature: Float?,
        ingestPrompt: () -> PromptIngest,
    ): Flow<String> = flow {
        require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }
        lock.withLock {
            check(!closed) { "This session is closed." }
            val started = clock()
            engine.beginGeneration(grammar?.gbnf, grammar?.root, temperature)
            val prompt = ingestPrompt()
            val promptDone = clock()
            val decoder = Utf8StreamDecoder()
            val stopper = StopSequenceMatcher(stop)
            var generated = 0
            var firstToken: Long? = null
            var reason: StopReason? = null
            try {
                while (true) {
                    if (closing) {
                        reason = StopReason.CANCELLED
                        break
                    }
                    if (generated >= maxTokens) {
                        reason = StopReason.MAX_TOKENS
                        break
                    }
                    currentCoroutineContext().ensureActive()
                    val bytes = engine.nextToken()
                    if (bytes == null) {
                        reason = if (engine.usedTokens() + 1 >= engine.contextSize()) {
                            StopReason.CONTEXT_FULL
                        } else {
                            StopReason.END_OF_GENERATION
                        }
                        break
                    }
                    if (firstToken == null) firstToken = clock()
                    generated++
                    val text = stopper.push(decoder.push(bytes))
                    if (text.isNotEmpty()) emit(text)
                    if (stopper.stopped) {
                        reason = StopReason.STOP_SEQUENCE
                        break
                    }
                }
                if (reason != StopReason.STOP_SEQUENCE) {
                    val tail = decoder.flush()
                    var out = if (tail.isNotEmpty()) stopper.push(tail) else ""
                    if (stopper.stopped) reason = StopReason.STOP_SEQUENCE else out += stopper.flush()
                    if (out.isNotEmpty()) emit(out)
                }
            } finally {
                lastStats = GenerationStats(
                    promptTokens = prompt.promptTokens,
                    generatedTokens = generated,
                    generationMillis = clock() - promptDone,
                    promptMillis = promptDone - started,
                    timeToFirstTokenMillis = firstToken?.let { it - started },
                    cachedPromptTokens = prompt.reusedTokens,
                    stopReason = reason ?: StopReason.CANCELLED,
                )
            }
        }
    }.flowOn(dispatcher)

    /**
     * Saves the session content (the KV cache and its tokens) to [file], so the conversation can be resumed later,
     * even after the app restarts, without processing the prompt again. Restore it with [loadState] on a session of
     * the same model. The file holds the whole context: expect tens of megabytes for a long conversation.
     */
    suspend fun saveState(file: File) = withContext(dispatcher) {
        lock.withLock {
            check(!closed) { "This session is closed." }
            val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            engine.saveState(tmp.absolutePath)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Could not move the saved state to ${file.absolutePath}")
            }
        }
    }

    /**
     * Replaces what this session holds with a state saved by [saveState] and returns the number of tokens restored.
     *
     * The file must come from the same model file and library version, and fit in this session's context window;
     * otherwise this throws [IOException] and the session is left empty. Continue with [chat] (pass the same
     * conversation plus the new turn: the restored part is reused) or with [generate] (`addSpecial = false`).
     */
    suspend fun loadState(file: File): Int = withContext(dispatcher) {
        lock.withLock {
            check(!closed) { "This session is closed." }
            if (!file.isFile) throw FileNotFoundException("No saved state at ${file.absolutePath}")
            engine.loadState(file.absolutePath)
        }
    }

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
        /**
         * The session's own thread. It computes a share of every token next to the native worker threads, which wait
         * for it at each step, so it runs at display priority: at the default priority it loses the fast cores to
         * the UI and slows the whole step down. On a desktop JVM (host tests) the priority call is skipped.
         */
        fun newSessionDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread({
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                    r.run()
                }, "llama-session").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}
