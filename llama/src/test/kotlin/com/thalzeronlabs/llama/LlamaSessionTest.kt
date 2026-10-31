package com.thalzeronlabs.llama

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaSessionTest {

    /** Replays a fixed list of token byte chunks, like a model that always says the same thing. */
    private class FakeEngine(private val tokens: List<ByteArray>, private val context: Int = 2048) : Engine {
        val ingested = mutableListOf<Pair<String, Boolean>>()
        var resets = 0
        var closed = false
        var position = 0
        private var next = 0

        override fun ingest(text: String, addSpecial: Boolean): Int {
            ingested += text to addSpecial
            val count = text.split(" ").size
            position += count
            return count
        }

        override fun nextToken(): ByteArray? {
            if (next >= tokens.size) return null
            position++
            return tokens[next++]
        }

        override fun reset() {
            resets++
            position = 0
            next = 0
        }

        override fun usedTokens() = position
        override fun contextSize() = context
        override fun close() {
            closed = true
        }
    }

    private fun tokens(vararg pieces: String) = pieces.map { it.toByteArray() }

    private fun session(engine: Engine, clock: () -> Long = { 0L }) =
        LlamaSession(engine, formatChat = { msgs -> msgs.joinToString("|") { "${it.role.wireName}:${it.content}" } }, clock = clock)

    @Test
    fun `generate streams every token and stops at end of generation`() = runTest {
        val engine = FakeEngine(tokens("The", " sky", " is", " blue", "."))
        val out = session(engine).generate("Why is the sky blue?").toList()
        assertEquals("The sky is blue.", out.joinToString(""))
        assertEquals(listOf("Why is the sky blue?" to true), engine.ingested)
    }

    @Test
    fun `maxTokens caps the answer`() = runTest {
        val engine = FakeEngine(tokens("a", "b", "c", "d", "e"))
        val s = session(engine)
        assertEquals("abc", s.generate("go", maxTokens = 3).toList().joinToString(""))
        assertEquals(3, s.lastStats?.generatedTokens)
    }

    @Test
    fun `an emoji split across tokens is emitted whole`() = runTest {
        val rocket = "🚀".toByteArray()
        val engine = FakeEngine(listOf("go ".toByteArray(), rocket.copyOfRange(0, 2), rocket.copyOfRange(2, 4)))
        val out = session(engine).generate("x").toList()
        assertEquals(listOf("go ", "🚀"), out)
    }

    @Test
    fun `cancelling the collector stops generation early`() = runTest {
        val engine = FakeEngine(tokens(*Array(100) { "t$it " }))
        val s = session(engine)
        val firstTwo = s.generate("x").take(2).toList()
        assertEquals(listOf("t0 ", "t1 "), firstTwo)
        assertTrue("generation should stop shortly after cancellation", s.lastStats!!.generatedTokens < 100)
    }

    @Test
    fun `chat resets the session and renders the template without a second BOS`() = runTest {
        val engine = FakeEngine(tokens("Hi", "!"))
        val s = session(engine)
        val answer = s.chat(listOf(ChatMessage.system("Be nice"), ChatMessage.user("Hello"))).toList()
        assertEquals("Hi!", answer.joinToString(""))
        assertEquals(1, engine.resets)
        assertEquals(listOf("system:Be nice|user:Hello" to false), engine.ingested)
    }

    @Test
    fun `stats measure the generation time`() = runTest {
        var now = 1_000L
        val engine = FakeEngine(tokens("a", "b", "c", "d"))
        val s = session(engine, clock = { now.also { now += 500 } })
        s.generate("count with me").toList()
        val stats = s.lastStats!!
        assertEquals(3, stats.promptTokens)
        assertEquals(4, stats.generatedTokens)
        assertEquals(500, stats.generationMillis)
    }

    @Test
    fun `two generations never interleave`() = runBlocking {
        val engine = FakeEngine(tokens("1", "2", "3"))
        val s = session(engine)
        val started = CompletableDeferred<Unit>()
        val first = async { s.generate("a").toList().also { started.complete(Unit) } }
        val second = async { started.await(); engine.reset(); s.generate("b").toList() }
        assertEquals("123", first.await().joinToString(""))
        assertEquals("123", second.await().joinToString(""))
    }

    @Test
    fun `close frees the native session once and rejects new work`() = runBlocking {
        val engine = FakeEngine(tokens("a"))
        val s = session(engine)
        s.close()
        s.close()
        assertTrue(engine.closed)
        val failure = runCatching { s.generate("x").toList() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `reset and counters go through the engine`() = runBlocking {
        val engine = FakeEngine(tokens("a", "b"), context = 4096)
        val s = session(engine)
        s.generate("one two").toList()
        assertEquals(4, s.usedTokens())
        assertEquals(4096, s.contextSize())
        launch { s.reset() }.join()
        assertEquals(0, s.usedTokens())
    }
}
