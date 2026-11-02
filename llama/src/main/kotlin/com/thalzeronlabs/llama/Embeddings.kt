package com.thalzeronlabs.llama

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Cosine similarity of two embeddings: 1 means same direction, 0 unrelated, -1 opposite.
 * For vectors from [LlamaModel.embed] (normalized by default) this equals their dot product.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vectors have different sizes: ${a.size} and ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0.0 || normB == 0.0) return 0f
    return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
}

/** Scales [vector] to length 1 (a zero vector stays zero). */
internal fun l2Normalize(vector: FloatArray): FloatArray {
    var sum = 0.0
    for (v in vector) sum += v * v
    if (sum == 0.0) return vector
    val inv = (1.0 / sqrt(sum)).toFloat()
    return FloatArray(vector.size) { vector[it] * inv }
}

/**
 * Runs embeddings for a [LlamaModel]: creates the native embedding context on first use and lets one call in
 * at a time (a llama.cpp context is not thread-safe).
 */
internal class Embedder(
    private val create: () -> EmbeddingEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()
    private var engine: EmbeddingEngine? = null
    @Volatile private var closed = false

    suspend fun embed(text: String, normalize: Boolean): FloatArray {
        require(text.isNotEmpty()) { "Nothing to embed: the text is empty." }
        return withContext(dispatcher) {
            lock.withLock {
                check(!closed) { "This model is closed." }
                val e = engine ?: create().also { engine = it }
                val raw = e.embed(text)
                if (normalize) l2Normalize(raw) else raw
            }
        }
    }

    fun close() {
        if (closed) return
        runBlocking {
            lock.withLock {
                closed = true
                engine?.close()
                engine = null
            }
        }
    }
}
