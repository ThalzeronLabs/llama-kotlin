package com.thalzeronlabs.llama.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTest {

    @Test
    fun `defaults are filled in from the model argument alone`() {
        val c = BenchmarkConfig.from(mapOf("model" to "qwen.gguf"))
        assertEquals("qwen.gguf", c.model)
        assertEquals(512, c.promptTokens)
        assertEquals(128, c.generateTokens)
        assertEquals(3, c.repetitions)
        assertEquals(0, c.threads)
        assertEquals(512 + 128 + 64, c.contextSize)
        assertEquals(512, c.batchSize)
    }

    @Test
    fun `arguments override the defaults`() {
        val c = BenchmarkConfig.from(mapOf("model" to "/sdcard/m.gguf", "pp" to "64", "tg" to " 32 ", "reps" to "5", "threads" to "4"))
        assertEquals(64, c.promptTokens)
        assertEquals(32, c.generateTokens)
        assertEquals(5, c.repetitions)
        assertEquals(4, c.threads)
        assertEquals(64, c.batchSize)
        assertEquals(64 + 32 + 64, c.contextSize)
    }

    @Test
    fun `bad arguments are explained`() {
        fun message(args: Map<String, String>) =
            runCatching { BenchmarkConfig.from(args) }.exceptionOrNull()!!.message!!
        assertTrue(message(emptyMap()).contains("model"))
        assertTrue(message(mapOf("model" to "m", "pp" to "lots")).contains("pp"))
        assertTrue(message(mapOf("model" to "m", "tokens" to "1")).contains("tokens"))
        assertTrue(message(mapOf("model" to "m", "ctx" to "100")).contains("ctx"))
    }

    @Test
    fun `summary uses the sample standard deviation`() {
        val s = Summary.of(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))
        assertEquals(5.0, s.mean, 1e-9)
        assertEquals(2.138, s.stdDev, 1e-3)
        assertEquals("5.00 ± 2.14", s.format())
        assertEquals("3.00", Summary.of(listOf(3.0)).format())
    }

    @Test
    fun `run speeds come from tokens and milliseconds`() {
        val r = Run(promptTokens = 512, promptMillis = 2_000, generatedTokens = 64, generationMillis = 8_000, timeToFirstTokenMillis = 2_100)
        assertEquals(256.0, r.promptTokensPerSecond, 1e-9)
        assertEquals(8.0, r.generationTokensPerSecond, 1e-9)
        assertEquals(0.0, r.copy(promptMillis = 0).promptTokensPerSecond, 1e-9)
    }

    @Test
    fun `the report holds a pasteable table and the context of the numbers`() {
        val config = BenchmarkConfig(model = "m.gguf", promptTokens = 128, generateTokens = 32, repetitions = 2)
        val env = Environment("Acme Phone", "Acme X1", "arm64-v8a", "16 (API 36)", 8, "m.gguf", 3L * 1_048_576, "0.2.0", "none", "light")
        val runs = listOf(Run(128, 1_000, 32, 4_000, 1_050), Run(128, 1_000, 30, 3_000, 1_040))
        val md = Report.markdown(config, env, runs)
        assertTrue(md, md.contains("| Acme Phone | Acme X1 | m.gguf | auto | 128.00 ± 0.00 | 9.00 ± 1.41 | 1045 ± 7 |"))
        assertTrue(md.contains("generated tokens per run: 32, 30 (the model ended some answers early)"))
        assertTrue(md.contains("Thermal status: none before, light after"))
        assertTrue(md.contains("3.0 MiB"))
    }

    @Test
    fun `the prompt is the longest text that fits the token budget`() {
        // A fake tokenizer: one token per word plus a begin-of-sequence token.
        val count = { text: String -> text.split(' ').size + 1 }
        val prompt = Benchmark.promptOf(100) { count(it) }
        assertEquals(100, count(prompt))
        assertEquals(1, count(Benchmark.promptOf(1) { count(it) }) - 1)
    }
}
