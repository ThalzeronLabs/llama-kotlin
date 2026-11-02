package com.thalzeronlabs.llama

/**
 * The few native operations a [LlamaSession] needs. Behind an interface so the session logic
 * (streaming, cancellation, limits, stop sequences, statistics) can be unit-tested on the JVM without a model.
 */
internal interface Engine {
    /**
     * Rebuilds the sampler before a generation: fresh random state from the seed, and the
     * grammar (GBNF text + root rule), if any. Throws [IllegalArgumentException] if the grammar does not parse.
     */
    fun beginGeneration(grammar: String?, grammarRoot: String?)

    /** Appends text to what the session holds and returns how many tokens it took. */
    fun ingest(text: String, addSpecial: Boolean): Int

    /**
     * Makes the session hold exactly [text], keeping the longest prefix already in the KV cache.
     * Returns the prompt size and how many of its tokens were reused.
     */
    fun ingestReusingPrefix(text: String, addSpecial: Boolean): PromptIngest

    /** Generates one token; null means the model finished (or the context is full). */
    fun nextToken(): ByteArray?

    fun reset()
    fun usedTokens(): Int
    fun contextSize(): Int

    /** Writes the KV cache and token history to [path]. */
    fun saveState(path: String)

    /** Replaces the session content with a file written by [saveState]; returns the number of tokens restored. */
    fun loadState(path: String): Int

    fun close()
}

internal data class PromptIngest(val promptTokens: Int, val reusedTokens: Int)

internal class NativeEngine(private val handle: Long) : Engine {
    override fun beginGeneration(grammar: String?, grammarRoot: String?) =
        NativeBridge.beginGeneration(handle, grammar, grammarRoot)

    override fun ingest(text: String, addSpecial: Boolean) = NativeBridge.ingest(handle, text, addSpecial)

    override fun ingestReusingPrefix(text: String, addSpecial: Boolean): PromptIngest {
        val (total, reused) = NativeBridge.ingestReusingPrefix(handle, text, addSpecial)
        return PromptIngest(total, reused)
    }

    override fun nextToken(): ByteArray? = NativeBridge.nextToken(handle)
    override fun reset() = NativeBridge.reset(handle)
    override fun usedTokens() = NativeBridge.usedTokens(handle)
    override fun contextSize() = NativeBridge.contextSize(handle)
    override fun saveState(path: String) = NativeBridge.saveState(handle, path)
    override fun loadState(path: String) = NativeBridge.loadState(handle, path)
    override fun close() = NativeBridge.freeSession(handle)
}

/** The native side of [LlamaModel.embed]: one embedding context, used by one caller at a time. */
internal interface EmbeddingEngine {
    /** The raw (not normalized) pooled embedding of [text]. */
    fun embed(text: String): FloatArray
    fun close()
}

internal class NativeEmbeddingEngine(private val handle: Long) : EmbeddingEngine {
    override fun embed(text: String): FloatArray = NativeBridge.embed(handle, text)
    override fun close() = NativeBridge.freeEmbedder(handle)
}
