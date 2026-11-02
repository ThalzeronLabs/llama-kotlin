package com.thalzeronlabs.llama.benchmark

import com.thalzeronlabs.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the benchmark loop end to end against a real (tiny) model on the desktop JVM, to check the plumbing.
 * Skipped unless `-Pllama.nativeLibDir` and `-Pllama.testModel` are given. Its speeds are a host CPU's, not a phone's,
 * so it asserts on structure only and prints nothing anywhere.
 */
class BenchmarkHostTest {

    @Test
    fun `runs the requested repetitions and reports the prompt size`() = runBlocking {
        val file = System.getProperty("llama.testModel")?.let(::File)
        assumeTrue("needs -Pllama.testModel", file?.isFile == true)
        val model = LlamaModel.load(file!!)
        try {
            val config = BenchmarkConfig(model = file.name, promptTokens = 64, generateTokens = 16, repetitions = 2, threads = 2)
            val lines = mutableListOf<String>()
            val runs = Benchmark(config) { lines += it }.run(model)
            assertEquals(2, runs.size)
            runs.forEach {
                assertTrue(it.promptTokens in 60..64)
                assertTrue(it.generatedTokens in 1..16)
                assertTrue(it.promptTokensPerSecond > 0)
            }
            assertTrue(lines.first().startsWith("Prompt: "))
        } finally {
            model.close()
        }
    }
}
