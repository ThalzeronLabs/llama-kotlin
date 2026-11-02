package com.thalzeronlabs.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A GGUF model loaded in memory. Load it once, open as many [LlamaSession]s as you need, close it when done
 * (closing the model also closes its sessions).
 *
 * ```kotlin
 * val model = LlamaModel.load(File(filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"))
 * model.newSession().use { session ->
 *     session.chat(listOf(ChatMessage.user("Hi!"))).collect { print(it) }
 * }
 * model.close()
 * ```
 */
class LlamaModel private constructor(
    private val handle: Long,
    private val params: ModelParams,
) : Closeable {

    private val sessions = CopyOnWriteArraySet<LlamaSession>()
    private val embedder = Embedder(
        create = {
            NativeEmbeddingEngine(NativeBridge.newEmbedder(handle, params.embeddingContextSize, params.embeddingThreads))
        },
    )
    @Volatile private var closed = false

    /** Opens a new conversation with its own context window. */
    fun newSession(params: SessionParams = SessionParams()): LlamaSession {
        check(!closed) { "This model is closed." }
        val sessionHandle = NativeBridge.newSession(
            handle,
            params.contextSize,
            params.batchSize,
            params.threads,
            params.temperature,
            params.topK,
            params.topP,
            params.minP,
            params.seed,
        )
        val session = LlamaSession(
            engine = NativeEngine(sessionHandle),
            formatChat = { formatChat(it) },
            onClose = { sessions.remove(it) },
        )
        sessions += session
        return session
    }

    /** Renders [messages] with the chat template stored inside the GGUF file. */
    fun formatChat(messages: List<ChatMessage>, addAssistantTurn: Boolean = true): String {
        check(!closed) { "This model is closed." }
        return NativeBridge.applyChatTemplate(
            handle,
            messages.map { it.role.wireName }.toTypedArray(),
            messages.map { it.content }.toTypedArray(),
            addAssistantTurn,
        )
    }

    /** How many tokens [text] takes with this model's tokenizer (with the begin-of-sequence token if [addSpecial]). */
    fun tokenCount(text: String, addSpecial: Boolean = true): Int {
        check(!closed) { "This model is closed." }
        return NativeBridge.tokenCount(handle, text, addSpecial)
    }

    /** Length of the vectors returned by [embed]. */
    val embeddingSize: Int
        get() {
            check(!closed) { "This model is closed." }
            return NativeBridge.embeddingSize(handle)
        }

    /**
     * Turns [text] into an embedding: a vector that captures its meaning, for semantic search, clustering or
     * deduplication. Compare two vectors with [cosineSimilarity].
     *
     * Use a model made for embeddings, such as `bge-small-en-v1.5` in GGUF (the one this project's tests run): the
     * pooling it declares (mean, CLS, last token) is used. A chat model without a pooling type gets mean pooling,
     * which usually gives weaker vectors. Reranking models are refused with [IllegalStateException].
     *
     * Runs off the main thread; calls are serialized. The first call creates the embedding context
     * ([ModelParams.embeddingContextSize] tokens). Texts longer than that throw [ContextFullException]: split them.
     * @param normalize scale the vector to length 1 (then cosine similarity is a plain dot product).
     */
    suspend fun embed(text: String, normalize: Boolean = true): FloatArray {
        check(!closed) { "This model is closed." }
        return embedder.embed(text, normalize)
    }

    override fun close() {
        if (closed) return
        sessions.forEach { it.close() }
        embedder.close()
        closed = true
        NativeBridge.freeModel(handle)
    }

    companion object {
        /** Loads a GGUF model from disk on [Dispatchers.IO]. Throws [java.io.IOException] if the file is not a valid model. */
        suspend fun load(file: File, params: ModelParams = ModelParams()): LlamaModel = withContext(Dispatchers.IO) {
            if (!file.isFile) throw FileNotFoundException("No model at ${file.absolutePath}")
            LlamaModel(NativeBridge.loadModel(file.absolutePath, params.useMmap), params)
        }
    }
}
