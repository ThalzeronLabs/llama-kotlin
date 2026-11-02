package com.thalzeronlabs.llama.benchmark

import java.util.Locale
import kotlin.math.sqrt

/** Mean and sample standard deviation of a few measurements. */
data class Summary(val mean: Double, val stdDev: Double, val count: Int) {
    fun format(decimals: Int = 2): String =
        if (count < 2) "%.${decimals}f".format(Locale.ROOT, mean)
        else "%.${decimals}f ± %.${decimals}f".format(Locale.ROOT, mean, stdDev)

    companion object {
        fun of(values: List<Double>): Summary {
            require(values.isNotEmpty()) { "No measurements" }
            val mean = values.average()
            val variance = if (values.size < 2) 0.0 else values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            return Summary(mean, sqrt(variance), values.size)
        }
    }
}

/** One measured repetition. */
data class Run(
    val promptTokens: Int,
    val promptMillis: Long,
    val generatedTokens: Int,
    val generationMillis: Long,
    val timeToFirstTokenMillis: Long?,
) {
    val promptTokensPerSecond: Double get() = if (promptMillis <= 0) 0.0 else promptTokens * 1000.0 / promptMillis
    val generationTokensPerSecond: Double get() = if (generationMillis <= 0) 0.0 else generatedTokens * 1000.0 / generationMillis
}

/** Where the numbers came from, printed next to them so a result can be compared and reproduced. */
data class Environment(
    val device: String,
    val soc: String,
    val abi: String,
    val android: String,
    val cpuCores: Int,
    val modelFile: String,
    val modelBytes: Long,
    val libraryVersion: String,
    val thermalBefore: String,
    val thermalAfter: String,
)

object Report {

    fun markdown(config: BenchmarkConfig, env: Environment, runs: List<Run>): String {
        val pp = Summary.of(runs.map { it.promptTokensPerSecond })
        val tg = Summary.of(runs.map { it.generationTokensPerSecond })
        val ttft = runs.mapNotNull { it.timeToFirstTokenMillis?.toDouble() }.takeIf { it.isNotEmpty() }?.let(Summary::of)
        val generated = runs.map { it.generatedTokens }
        val threads = if (config.threads == 0) "auto" else config.threads.toString()
        return buildString {
            appendLine("## llama-kotlin benchmark")
            appendLine()
            appendLine("| Device | SoC | Model | Threads | pp${runs.first().promptTokens} (t/s) | tg${config.generateTokens} (t/s) | TTFT (ms) |")
            appendLine("|---|---|---|---|---|---|---|")
            appendLine("| ${env.device} | ${env.soc} | ${env.modelFile} | $threads | ${pp.format()} | ${tg.format()} | ${ttft?.format(0) ?: "-"} |")
            appendLine()
            appendLine("- Repetitions: ${runs.size} (after 1 warm-up run), mean ± sample standard deviation")
            appendLine("- Prompt tokens: ${runs.first().promptTokens}; generated tokens per run: ${generated.joinToString()}" +
                if (generated.any { it < config.generateTokens }) " (the model ended some answers early)" else "")
            appendLine("- Context: ${config.contextSize}, batch: ${config.batchSize}")
            appendLine("- Model file: ${env.modelFile} (${"%.1f".format(Locale.ROOT, env.modelBytes / 1_048_576.0)} MiB)")
            appendLine("- ABI: ${env.abi}, CPU cores: ${env.cpuCores}, Android ${env.android}")
            appendLine("- Thermal status: ${env.thermalBefore} before, ${env.thermalAfter} after")
            appendLine("- llama-kotlin ${env.libraryVersion}")
        }
    }
}
