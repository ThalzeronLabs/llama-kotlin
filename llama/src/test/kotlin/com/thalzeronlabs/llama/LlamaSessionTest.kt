package com.thalzeronlabs.llama

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class LlamaSessionTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * Replays a fixed list of token byte chunks, like a model that always says the same thing.
     * "Tokens" of a prompt are its space-separated words, which makes the prefix cache easy to reason about.
     */
    private class FakeEngine(private val tokens: List<ByteArray>, private val context: Int = 2048) : Engine {
        val ingested = mutableListOf<Pair<String, Boolean>>()
        val grammars = mutableListOf<Pair<String?, String?>>()
        val temperatures = mutableListOf<Float?>()
        val history = mutableListOf<String>()
        var resets = 0
        var closed = false
        var nextTokenCalls = 0
        private var next = 0

        override fun beginGeneration(grammar: String?, grammarRoot: String?, temperature: Float?) {
            grammars += grammar to grammarRoot
            temperatures += temperature
            next = 0
        }

        override fun ingest(text: String, addSpecial: Boolean): Int {
            ingested += text to addSpecial
            val words = text.split(" ")
            history += words
            return words.size
        }

        override fun ingestReusingPrefix(text: String, addSpecial: Boolean): PromptIngest {
            ingested += text to addSpecial
            val words = text.split(" ")
            var common = words.indices.takeWhile { it < history.size && history[it] == words[it] }.count()
            if (common == words.size) common--
            while (history.size > common) history.removeAt(history.lastIndex)
            history += words.drop(common)
            return PromptIngest(words.size, common)
        }

        override fun nextToken(): ByteArray? {
            nextTokenCalls++
            if (next >= tokens.size || history.size + 1 >= context) return null
            history += "<gen>"
            return tokens[next++]
        }

        override fun reset() {
            resets++
            history.clear()
            next = 0
        }

        override fun usedTokens() = history.size
        override fun contextSize() = context

        override fun saveState(path: String) {
            File(path).writeText(history.joinToString("\n"))
        }

        override fun loadState(path: String): Int {
            history.clear()
            history += File(path).readLines()
            return history.size
        }

        override fun close() {
            closed = true
        }
    }

    private fun tokens(vararg pieces: String) = pieces.map { it.toByteArray() }

    private fun session(engine: Engine, clock: () -> Long = { 0L }) =
        LlamaSession(engine, formatChat = { msgs -> msgs.joinToString(" ") { "${it.role.wireName}:${it.content}" } }, clock = clock)

    @Test
    fun `generate streams every token and stops at end of generation`() = runTest {
        val engine = FakeEngine(tokens("The", " sky", " is", " blue", "."))
        val s = session(engine)
        val out = s.generate("Why is the sky blue?").toList()
        assertEquals("The sky is blue.", out.joinToString(""))
        assertEquals(listOf("Why is the sky blue?" to true), engine.ingested)
        assertEquals(StopReason.END_OF_GENERATION, s.lastStats?.stopReason)
    }

    @Test
    fun `maxTokens caps the answer`() = runTest {
        val engine = FakeEngine(tokens("a", "b", "c", "d", "e"))
        val s = session(engine)
        assertEquals("abc", s.generate("go", maxTokens = 3).toList().joinToString(""))
        assertEquals(3, s.lastStats?.generatedTokens)
        assertEquals(StopReason.MAX_TOKENS, s.lastStats?.stopReason)
    }

    @Test
    fun `a full context window is reported as such`() = runTest {
        val engine = FakeEngine(tokens(*Array(500) { "x" }), context = 128)
        val s = session(engine)
        s.generate("one two three").toList()
        assertEquals(StopReason.CONTEXT_FULL, s.lastStats?.stopReason)
        assertEquals(127, s.usedTokens())
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
        assertEquals(StopReason.CANCELLED, s.lastStats?.stopReason)
    }

    // --- structured output -------------------------------------------------------------------------------------

    @Test
    fun `the grammar reaches the engine before the prompt`() = runTest {
        val engine = FakeEngine(tokens("yes"))
        val s = session(engine)
        val grammar = Grammar.gbnf("""answer ::= "yes" | "no"""", root = "answer")
        s.generate("Is water wet?", grammar = grammar).toList()
        assertEquals(listOf(grammar.gbnf to "answer"), engine.grammars)
    }

    @Test
    fun `without a grammar the sampler is still rebuilt for every generation`() = runTest {
        val engine = FakeEngine(tokens("a"))
        val s = session(engine)
        s.generate("x").toList()
        s.generate("y", addSpecial = false).toList()
        assertEquals(listOf<Pair<String?, String?>>(null to null, null to null), engine.grammars)
    }

    @Test
    fun `a grammar the engine rejects fails the generation before the prompt is processed`() = runTest {
        val engine = object : Engine by FakeEngine(tokens("a")) {
            var ingestCalls = 0
            override fun beginGeneration(grammar: String?, grammarRoot: String?, temperature: Float?) {
                if (grammar != null) throw IllegalArgumentException("bad grammar")
            }
            override fun ingest(text: String, addSpecial: Boolean): Int = 1.also { ingestCalls++ }
        }
        val failure = runCatching { session(engine).generate("x", grammar = Grammar.gbnf("root ::= (")).toList() }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, engine.ingestCalls)
    }

    // --- per-call temperature ------------------------------------------------------------------------------------

    @Test
    fun `a per-call temperature reaches the engine for that generation only`() = runTest {
        val engine = FakeEngine(tokens("a"))
        val s = session(engine)
        s.chat(listOf(ChatMessage.user("hi")), temperature = 0.2f).toList()
        s.chat(listOf(ChatMessage.user("hi")), temperature = 0f).toList()
        s.chat(listOf(ChatMessage.user("hi"))).toList()
        s.generate("x", addSpecial = false, temperature = 1.3f).toList()
        assertEquals(listOf(0.2f, 0f, null, 1.3f), engine.temperatures)
    }

    @Test
    fun `changing the temperature keeps the cache`() = runTest {
        val engine = FakeEngine(tokens("ok"))
        val s = session(engine)
        val messages = listOf(ChatMessage.user("a"), ChatMessage.user("b"))
        s.chat(messages, temperature = 0.9f).toList()
        s.chat(messages, temperature = 0.1f).toList()
        assertEquals(1, s.lastStats!!.cachedPromptTokens)
        assertEquals(0, engine.resets)
    }

    @Test
    fun `invalid per-call temperatures are rejected when the call is made`() {
        val s = session(FakeEngine(tokens("a")))
        for (bad in listOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val chat = runCatching { s.chat(listOf(ChatMessage.user("x")), temperature = bad) }.exceptionOrNull()
            assertTrue("chat accepted $bad", chat is IllegalArgumentException)
            val generate = runCatching { s.generate("x", temperature = bad) }.exceptionOrNull()
            assertTrue("generate accepted $bad", generate is IllegalArgumentException)
        }
    }

    // --- stop sequences ----------------------------------------------------------------------------------------

    @Test
    fun `a stop sequence ends the answer and is not emitted`() = runTest {
        val engine = FakeEngine(tokens("Hello", " there", "\nUser", ":", " more", " text"))
        val s = session(engine)
        val out = s.generate("x", stop = listOf("\nUser:")).toList().joinToString("")
        assertEquals("Hello there", out)
        assertEquals(StopReason.STOP_SEQUENCE, s.lastStats?.stopReason)
        assertEquals(4, s.lastStats?.generatedTokens)
    }

    @Test
    fun `text that only looks like a stop sequence is released`() = runTest {
        val engine = FakeEngine(tokens("a", "<", "b", ">"))
        val out = session(engine).generate("x", stop = listOf("<end>")).toList().joinToString("")
        assertEquals("a<b>", out)
    }

    @Test
    fun `a partial stop sequence at the very end is flushed`() = runTest {
        val engine = FakeEngine(tokens("done", " <en"))
        val s = session(engine)
        val out = s.generate("x", stop = listOf("<end>")).toList().joinToString("")
        assertEquals("done <en", out)
        assertEquals(StopReason.END_OF_GENERATION, s.lastStats?.stopReason)
    }

    @Test
    fun `chat supports stop sequences too`() = runTest {
        val engine = FakeEngine(tokens("4", "\n\n", "Q:"))
        val out = session(engine).chat(listOf(ChatMessage.user("2+2?")), stop = listOf("\n\n")).toList()
        assertEquals("4", out.joinToString(""))
    }

    // --- statistics --------------------------------------------------------------------------------------------

    @Test
    fun `stats measure prompt time, time to first token and generation time`() = runTest {
        var now = 1_000L
        val engine = FakeEngine(tokens("a", "b", "c", "d"))
        // Every clock read advances 500 ms: start, after prompt, first token, end.
        val s = session(engine, clock = { now.also { now += 500 } })
        s.generate("count with me").toList()
        val stats = s.lastStats!!
        assertEquals(3, stats.promptTokens)
        assertEquals(4, stats.generatedTokens)
        assertEquals(500, stats.promptMillis)
        assertEquals(1_000L, stats.timeToFirstTokenMillis)
        assertEquals(1_000, stats.generationMillis)
        assertEquals(4.0, stats.tokensPerSecond, 1e-9)
        assertEquals(6.0, stats.promptTokensPerSecond, 1e-9)
    }

    @Test
    fun `time to first token is null when nothing was generated`() = runTest {
        val s = session(FakeEngine(emptyList()))
        assertEquals(emptyList<String>(), s.generate("x").toList())
        assertNull(s.lastStats!!.timeToFirstTokenMillis)
        assertEquals(0, s.lastStats!!.generatedTokens)
    }

    // --- chat and the prefix cache -----------------------------------------------------------------------------

    @Test
    fun `chat renders the template without a second BOS`() = runTest {
        val engine = FakeEngine(tokens("Hi", "!"))
        val s = session(engine)
        val answer = s.chat(listOf(ChatMessage.system("Be-nice"), ChatMessage.user("Hello"))).toList()
        assertEquals("Hi!", answer.joinToString(""))
        assertEquals(listOf("system:Be-nice user:Hello" to false), engine.ingested)
    }

    @Test
    fun `a follow-up chat turn reuses the cached conversation`() = runTest {
        val engine = FakeEngine(tokens("ok"))
        val s = session(engine)
        val first = listOf(ChatMessage.system("sys"), ChatMessage.user("one"))
        s.chat(first).toList()
        assertEquals(0, s.lastStats!!.cachedPromptTokens)

        // The fake's history now holds "system:sys user:one <gen>"; the next prompt shares the first two words.
        s.chat(first + ChatMessage.assistant("ok") + ChatMessage.user("two")).toList()
        val stats = s.lastStats!!
        assertEquals(4, stats.promptTokens)
        assertEquals(2, stats.cachedPromptTokens)
        assertEquals(0, engine.resets)
    }

    @Test
    fun `sending the same conversation again still processes its last token`() = runTest {
        val engine = FakeEngine(tokens("ok"))
        val s = session(engine)
        val messages = listOf(ChatMessage.user("a"), ChatMessage.user("b"))
        s.chat(messages).toList()
        s.chat(messages).toList()
        assertEquals(1, s.lastStats!!.cachedPromptTokens)
    }

    // --- state save / restore ----------------------------------------------------------------------------------

    @Test
    fun `saved state can be restored into a fresh session`() = runTest {
        val file = File(tmp.root, "chat.state")
        val first = FakeEngine(tokens("ok"))
        val s1 = session(first)
        s1.chat(listOf(ChatMessage.user("remember-me"))).toList()
        s1.saveState(file)
        assertTrue(file.isFile)
        assertFalse(File(tmp.root, "chat.state.tmp").exists())

        val second = FakeEngine(tokens("ok"))
        val s2 = session(second)
        assertEquals(2, s2.loadState(file))
        assertEquals(2, s2.usedTokens())

        // Resuming the conversation only processes the new turn.
        s2.chat(listOf(ChatMessage.user("remember-me"), ChatMessage.user("next"))).toList()
        assertEquals(1, s2.lastStats!!.cachedPromptTokens)
    }

    @Test(expected = FileNotFoundException::class)
    fun `loading a missing state file fails clearly`() = runTest {
        session(FakeEngine(emptyList())).loadState(File(tmp.root, "nope.state"))
    }

    // --- concurrency and lifecycle -----------------------------------------------------------------------------

    @Test
    fun `two generations never interleave`() = runBlocking {
        val engine = FakeEngine(tokens("1", "2", "3"))
        val s = session(engine)
        val started = CompletableDeferred<Unit>()
        val first = async { s.generate("a").toList().also { started.complete(Unit) } }
        val second = async { started.await(); s.generate("b").toList() }
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
        val saveFailure = runCatching { s.saveState(File(tmp.root, "x")) }.exceptionOrNull()
        assertTrue(saveFailure is IllegalStateException)
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
