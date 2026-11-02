package com.thalzeronlabs.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class EmbeddingsTest {

    /** "Embeds" text as letter counts for a, b and c, which makes similarities easy to predict. */
    private class FakeEmbeddingEngine : EmbeddingEngine {
        val calls = AtomicInteger()
        val concurrent = AtomicInteger()
        var maxConcurrent = 0
        var closed = 0

        override fun embed(text: String): FloatArray {
            calls.incrementAndGet()
            val now = concurrent.incrementAndGet()
            maxConcurrent = maxOf(maxConcurrent, now)
            Thread.sleep(5)
            concurrent.decrementAndGet()
            return floatArrayOf(text.count { it == 'a' }.toFloat(), text.count { it == 'b' }.toFloat(), text.count { it == 'c' }.toFloat())
        }

        override fun close() {
            closed++
        }
    }

    @Test
    fun `cosine similarity of known vectors`() {
        assertEquals(1f, cosineSimilarity(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-6f)
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 5f)), 1e-6f)
        assertEquals(-1f, cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-6f)
        assertEquals(0f, cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `vectors of different sizes are rejected`() {
        cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f))
    }

    @Test
    fun `normalization gives unit length and keeps the direction`() {
        val v = l2Normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), v, 1e-6f)
        assertEquals(1.0, sqrt(v.sumOf { (it * it).toDouble() }), 1e-6)
        assertArrayEquals(floatArrayOf(0f, 0f), l2Normalize(floatArrayOf(0f, 0f)), 0f)
    }

    @Test
    fun `the engine is created lazily, once`() = runTest {
        var created = 0
        val engine = FakeEmbeddingEngine()
        val embedder = Embedder(create = { created++; engine }, dispatcher = Dispatchers.Unconfined)
        assertEquals(0, created)
        embedder.embed("aab", normalize = false)
        embedder.embed("abc", normalize = false)
        assertEquals(1, created)
        assertEquals(2, engine.calls.get())
    }

    @Test
    fun `raw and normalized vectors`() = runTest {
        val embedder = Embedder(create = { FakeEmbeddingEngine() }, dispatcher = Dispatchers.Unconfined)
        assertArrayEquals(floatArrayOf(3f, 4f, 0f), embedder.embed("aaabbbb", normalize = false), 0f)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f, 0f), embedder.embed("aaabbbb", normalize = true), 1e-6f)
    }

    @Test
    fun `semantic search ranks the closest text first`() = runTest {
        val embedder = Embedder(create = { FakeEmbeddingEngine() }, dispatcher = Dispatchers.Unconfined)
        val docs = listOf("cccc", "aaab", "abbb")
        val vectors = docs.map { embedder.embed(it, normalize = true) }
        val query = embedder.embed("aaaa", normalize = true)
        val ranked = docs.indices.sortedByDescending { cosineSimilarity(query, vectors[it]) }.map { docs[it] }
        assertEquals(listOf("aaab", "abbb", "cccc"), ranked)
    }

    @Test
    fun `concurrent calls never reach the native context at the same time`() = runBlocking {
        val engine = FakeEmbeddingEngine()
        val embedder = Embedder(create = { engine }, dispatcher = Dispatchers.Default)
        (1..16).map { async(Dispatchers.Default) { embedder.embed("a".repeat(it), normalize = true) } }.awaitAll()
        assertEquals(1, engine.maxConcurrent)
        assertEquals(16, engine.calls.get())
    }

    @Test
    fun `empty text is rejected before reaching native code`() = runTest {
        val engine = FakeEmbeddingEngine()
        val embedder = Embedder(create = { engine }, dispatcher = Dispatchers.Unconfined)
        assertTrue(runCatching { embedder.embed("", normalize = true) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, engine.calls.get())
    }

    @Test
    fun `close frees the engine once and rejects new work`() = runTest {
        val engine = FakeEmbeddingEngine()
        val embedder = Embedder(create = { engine }, dispatcher = Dispatchers.Unconfined)
        embedder.embed("a", normalize = true)
        embedder.close()
        embedder.close()
        assertEquals(1, engine.closed)
        assertTrue(runCatching { embedder.embed("a", normalize = true) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `closing before first use creates nothing`() {
        var created = 0
        val embedder = Embedder(create = { created++; FakeEmbeddingEngine() })
        embedder.close()
        assertEquals(0, created)
    }
}
