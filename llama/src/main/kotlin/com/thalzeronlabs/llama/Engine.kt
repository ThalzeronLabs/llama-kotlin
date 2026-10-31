package com.thalzeronlabs.llama

/**
 * The few native operations a [LlamaSession] needs. Behind an interface so the session logic
 * (streaming, cancellation, limits, statistics) can be unit-tested on the JVM without a model.
 */
internal interface Engine {
    /** Feeds text to the model and returns how many tokens it took. */
    fun ingest(text: String, addSpecial: Boolean): Int

    /** Generates one token; null means the model finished (or the context is full). */
    fun nextToken(): ByteArray?

    fun reset()
    fun usedTokens(): Int
    fun contextSize(): Int
    fun close()
}

internal class NativeEngine(private val handle: Long) : Engine {
    override fun ingest(text: String, addSpecial: Boolean) = NativeBridge.ingest(handle, text, addSpecial)
    override fun nextToken(): ByteArray? = NativeBridge.nextToken(handle)
    override fun reset() = NativeBridge.reset(handle)
    override fun usedTokens() = NativeBridge.usedTokens(handle)
    override fun contextSize() = NativeBridge.contextSize(handle)
    override fun close() = NativeBridge.freeSession(handle)
}
