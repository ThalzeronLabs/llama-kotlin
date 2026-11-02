package com.thalzeronlabs.llama.benchmark

import com.thalzeronlabs.llama.LlamaModel
import com.thalzeronlabs.llama.SessionParams
import kotlinx.coroutines.flow.collect

/**
 * Measures prompt processing (pp) and generation (tg) speed through the public llama-kotlin API, like an app would
 * see it: [LlamaSession.generate][com.thalzeronlabs.llama.LlamaSession.generate] with greedy sampling, the session
 * reset before every run.
 */
class Benchmark(private val config: BenchmarkConfig, private val log: (String) -> Unit) {

    suspend fun run(model: LlamaModel): List<Run> {
        val prompt = promptOf(model, config.promptTokens)
        log("Prompt: ${model.tokenCount(prompt)} tokens; generating ${config.generateTokens} tokens per run.")
        val params = SessionParams(
            contextSize = config.contextSize,
            batchSize = config.batchSize,
            threads = config.threads,
            temperature = 0f,
        )
        return model.newSession(params).use { session ->
            log("Warm-up run…")
            session.generate(prompt, maxTokens = minOf(16, config.generateTokens)).collect()

            (1..config.repetitions).map { i ->
                session.reset()
                session.generate(prompt, maxTokens = config.generateTokens).collect()
                val stats = checkNotNull(session.lastStats)
                Run(
                    promptTokens = stats.promptTokens,
                    promptMillis = stats.promptMillis,
                    generatedTokens = stats.generatedTokens,
                    generationMillis = stats.generationMillis,
                    timeToFirstTokenMillis = stats.timeToFirstTokenMillis,
                ).also {
                    log("Run $i/${config.repetitions}: pp %.2f t/s, tg %.2f t/s (%d tokens)".format(
                        java.util.Locale.ROOT, it.promptTokensPerSecond, it.generationTokensPerSecond, it.generatedTokens))
                }
            }
        }
    }

    companion object {
        private val WORDS = ("the quick brown fox jumps over the lazy dog while a small bird sings in the old " +
            "tree near the river and the children play in the warm afternoon sun").split(' ')

        /** Plain English text of at most [tokens] tokens (as close to it as whole words allow). */
        fun promptOf(model: LlamaModel, tokens: Int): String = promptOf(tokens) { model.tokenCount(it) }

        internal fun promptOf(tokens: Int, count: (String) -> Int): String {
            fun text(words: Int) = (0 until words).joinToString(" ") { WORDS[it % WORDS.size] }
            var low = 1
            var high = tokens * 2
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (count(text(mid)) <= tokens) low = mid else high = mid - 1
            }
            return text(low)
        }
    }
}
