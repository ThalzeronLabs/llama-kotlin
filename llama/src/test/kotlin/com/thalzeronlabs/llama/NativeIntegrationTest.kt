package com.thalzeronlabs.llama

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * Runs the real native library (built for the desktop JVM) against real GGUF models. Skipped unless the build is
 * given `-Pllama.nativeLibDir`, `-Pllama.testModel` and `-Pllama.testEmbeddingModel` (see CONTRIBUTING.md); the
 * "host integration" CI job sets them. Tiny models keep it fast: tinyllamas stories15M and bge-small.
 */
class NativeIntegrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun session(
        temperature: Float = 0f,
        seed: Int = 1,
        contextSize: Int = 512,
    ) = model!!.newSession(
        SessionParams(contextSize = contextSize, batchSize = minOf(contextSize, 512), threads = 2,
            temperature = temperature, seed = seed),
    )

    @Test
    fun `generates text and reports stats`() = runBlocking {
        session().use { s ->
            val text = s.generate("Once upon a time", maxTokens = 24).toList().joinToString("")
            assertTrue(text.isNotBlank())
            val stats = s.lastStats!!
            assertTrue(stats.promptTokens > 0)
            assertTrue(stats.generatedTokens > 0)
            assertNotNull(stats.timeToFirstTokenMillis)
            assertTrue(model!!.tokenCount("Once upon a time") == stats.promptTokens)
        }
    }

    @Test
    fun `the same seed gives the same answer, in new and reused sessions`() = runBlocking {
        val a = session(temperature = 0.9f, seed = 1234).use { it.generate("The dog", maxTokens = 32).toList().joinToString("") }
        val b = session(temperature = 0.9f, seed = 1234).use { it.generate("The dog", maxTokens = 32).toList().joinToString("") }
        assertEquals(a, b)
        session(temperature = 0.9f, seed = 1234).use { s ->
            val first = s.generate("The dog", maxTokens = 32).toList().joinToString("")
            s.reset()
            val second = s.generate("The dog", maxTokens = 32).toList().joinToString("")
            assertEquals(first, second)
            assertEquals(a, first)
        }
    }

    @Test
    fun `a GBNF grammar restricts the output`() = runBlocking {
        session(temperature = 0.8f).use { s ->
            repeat(3) {
                s.reset()
                val answer = s.generate("Is the sky blue?", grammar = Grammar.gbnf("""root ::= "yes" | "no""""), maxTokens = 8)
                    .toList().joinToString("")
                assertTrue("got: $answer", answer == "yes" || answer == "no")
                assertEquals(StopReason.END_OF_GENERATION, s.lastStats!!.stopReason)
            }
        }
    }

    @Test
    fun `a JSON schema grammar gives JSON that matches the schema`() = runBlocking {
        val schema = """
            {"type": "object",
             "properties": {
               "mood": {"enum": ["happy", "sad"]},
               "age": {"type": "integer"},
               "friendly": {"type": "boolean"},
               "name": {"type": "string", "maxLength": 12}
             },
             "required": ["mood", "age", "friendly", "name"]}
        """
        session(temperature = 0.7f, seed = 3).use { s ->
            val json = s.generate("Lily was a girl.", grammar = Grammar.jsonSchema(schema), maxTokens = 128)
                .toList().joinToString("")
            val value = Json.parse(json) as Map<*, *>
            assertEquals(listOf("mood", "age", "friendly", "name"), value.keys.toList())
            assertTrue(value["mood"] in listOf("happy", "sad"))
            assertTrue(value["age"] is Long)
            assertTrue(value["friendly"] is Boolean)
            assertTrue((value["name"] as String).length <= 12)
        }
    }

    @Test
    fun `text outside the BMP reaches llama_cpp as real UTF-8`() = runBlocking {
        // With modified UTF-8 (the JNI default) the emoji would arrive as surrogate bytes that no token can match.
        session().use { s ->
            val out = s.generate("A", grammar = Grammar.gbnf("root ::= \"ok 🚀\""), maxTokens = 16).toList().joinToString("")
            assertEquals("ok 🚀", out)
        }
        assertTrue(model!!.tokenCount("🚀", addSpecial = false) > 0)
    }

    @Test
    fun `an invalid GBNF grammar is reported as IllegalArgumentException`() = runBlocking {
        session().use { s ->
            val failure = runCatching { s.generate("x", grammar = Grammar.gbnf("root ::= (")).toList() }.exceptionOrNull()
            assertTrue("got $failure", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `stop sequences cut the output where they appear`() = runBlocking {
        session().use { s ->
            val full = s.generate("Once upon a time", maxTokens = 48).toList().joinToString("")
            val stop = full.substring(full.length / 2, full.length / 2 + 4)
            s.reset()
            val cut = s.generate("Once upon a time", maxTokens = 48, stop = listOf(stop)).toList().joinToString("")
            assertEquals(full.substringBefore(stop), cut)
            assertEquals(StopReason.STOP_SEQUENCE, s.lastStats!!.stopReason)
        }
    }

    @Test
    fun `a saved state resumes exactly where it left off`() = runBlocking {
        val file = File(tmp.root, "story.state")
        val (used, expected) = session().use { s ->
            s.generate("Once upon a time", maxTokens = 16).toList()
            s.saveState(file)
            val used = s.usedTokens()
            used to s.generate("", addSpecial = false, maxTokens = 16).toList().joinToString("")
        }
        assertTrue(file.length() > 0)
        session().use { s ->
            assertEquals(used, s.loadState(file))
            assertEquals(used, s.usedTokens())
            assertEquals(expected, s.generate("", addSpecial = false, maxTokens = 16).toList().joinToString(""))
        }
    }

    @Test
    fun `a state does not load into a smaller context`() = runBlocking {
        val file = File(tmp.root, "big.state")
        val small = session(contextSize = 256).use { it.contextSize() } // llama.cpp may round the size up
        session(contextSize = 2048).use { s ->
            s.generate("Once upon a time. ".repeat(small / 4), maxTokens = 4).toList()
            assertTrue(s.usedTokens() > small)
            s.saveState(file)
        }
        session(contextSize = 256).use { s ->
            val failure = runCatching { s.loadState(file) }.exceptionOrNull()
            assertTrue("got $failure", failure is IOException)
            assertEquals(0, s.usedTokens())
        }
    }

    @Test
    fun `chat reuses the cached prefix and answers like a fresh session`() = runBlocking {
        // stories15M has no chat template, so this builds the session by hand with a plain-text one.
        val template: (List<ChatMessage>) -> String = { msgs ->
            msgs.joinToString("\n") { "${it.role.wireName}: ${it.content}" } + "\nassistant:"
        }
        fun newRawSession(): LlamaSession {
            val handle = NativeBridge.newSession(modelHandle, 512, 512, 2, 0f, 40, 0.95f, 0.05f, 1)
            return LlamaSession(NativeEngine(handle), template)
        }
        val turn1 = listOf(ChatMessage.user("Tell me about a cat."))
        val turn2 = turn1 + ChatMessage.user("And a dog?")

        val cachedAnswer = newRawSession().use { s ->
            s.chat(turn1, maxTokens = 16).toList()
            val answer = s.chat(turn2, maxTokens = 16).toList().joinToString("")
            val stats = s.lastStats!!
            assertTrue("nothing was reused: $stats", stats.cachedPromptTokens > 0)
            assertTrue(stats.cachedPromptTokens < stats.promptTokens)
            answer
        }
        val freshAnswer = newRawSession().use { s -> s.chat(turn2, maxTokens = 16).toList().joinToString("") }
        assertEquals(freshAnswer, cachedAnswer)
    }

    @Test
    fun `a prompt larger than the context throws ContextFullException`() = runBlocking {
        session(contextSize = 128).use { s ->
            val failure = runCatching { s.generate("word ".repeat(400)).toList() }.exceptionOrNull()
            assertTrue("got $failure", failure is ContextFullException)
        }
    }

    @Test
    fun `embeddings place similar sentences closer together`() = runBlocking {
        val m = embeddingModel!!
        val cat = m.embed("The cat sleeps on the sofa.")
        val kitten = m.embed("A kitten is napping on the couch.")
        val tax = m.embed("Quarterly tax filing deadlines for small businesses.")
        assertEquals(m.embeddingSize, cat.size)
        assertEquals(1.0, sqrt(cat.sumOf { (it * it).toDouble() }), 1e-3)
        assertTrue(cosineSimilarity(cat, kitten) > cosineSimilarity(cat, tax))
        assertEquals(cat.toList(), m.embed("The cat sleeps on the sofa.").toList())
        val raw = m.embed("The cat sleeps on the sofa.", normalize = false)
        assertEquals(1f, cosineSimilarity(raw, cat), 1e-4f)
    }

    @Test
    fun `text longer than the embedding context throws ContextFullException`() = runBlocking {
        val failure = runCatching { embeddingModel!!.embed("word ".repeat(2000)) }.exceptionOrNull()
        assertTrue("got $failure", failure is ContextFullException)
    }

    companion object {
        private var model: LlamaModel? = null
        private var embeddingModel: LlamaModel? = null
        private var modelHandle = 0L

        private fun property(name: String) = System.getProperty(name)?.let(::File)?.takeIf { it.isFile }

        @BeforeClass @JvmStatic
        fun loadModels() {
            val generative = property("llama.testModel")
            val embedding = property("llama.testEmbeddingModel")
            assumeTrue("host integration tests need -Pllama.testModel and -Pllama.testEmbeddingModel", generative != null && embedding != null)
            runBlocking {
                model = LlamaModel.load(generative!!)
                embeddingModel = LlamaModel.load(embedding!!, ModelParams(embeddingContextSize = 512))
            }
            modelHandle = NativeBridge.loadModel(generative!!.absolutePath, true)
        }

        @AfterClass @JvmStatic
        fun closeModels() {
            model?.close()
            embeddingModel?.close()
            if (modelHandle != 0L) NativeBridge.freeModel(modelHandle)
        }
    }
}
