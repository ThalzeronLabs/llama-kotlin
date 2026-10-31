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
class LlamaModel private constructor(private val handle: Long) : Closeable {

    private val sessions = CopyOnWriteArraySet<LlamaSession>()
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

    override fun close() {
        if (closed) return
        sessions.forEach { it.close() }
        closed = true
        NativeBridge.freeModel(handle)
    }

    companion object {
        /** Loads a GGUF model from disk on [Dispatchers.IO]. Throws [java.io.IOException] if the file is not a valid model. */
        suspend fun load(file: File, params: ModelParams = ModelParams()): LlamaModel = withContext(Dispatchers.IO) {
            if (!file.isFile) throw FileNotFoundException("No model at ${file.absolutePath}")
            LlamaModel(NativeBridge.loadModel(file.absolutePath, params.useMmap))
        }
    }
}
