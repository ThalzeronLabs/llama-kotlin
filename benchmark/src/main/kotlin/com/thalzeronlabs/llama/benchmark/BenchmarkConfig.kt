package com.thalzeronlabs.llama.benchmark

/**
 * What to measure, read from the `-e key value` arguments of `adb shell am instrument`.
 *
 * | key       | default           | meaning                                                         |
 * |-----------|-------------------|-----------------------------------------------------------------|
 * | `model`   | (required)        | GGUF file: absolute path, or a file name in the app's files dir |
 * | `pp`      | 512               | prompt tokens to process                                        |
 * | `tg`      | 128               | tokens to generate                                              |
 * | `reps`    | 3                 | measured repetitions (after one warm-up run)                    |
 * | `threads` | 0                 | CPU threads, 0 = the library's default (cores - 2, at most 4)   |
 * | `batch`   | min(512, pp)      | prompt batch size                                               |
 * | `ctx`     | pp + tg + 64      | context window                                                  |
 */
data class BenchmarkConfig(
    val model: String,
    val promptTokens: Int = 512,
    val generateTokens: Int = 128,
    val repetitions: Int = 3,
    val threads: Int = 0,
    val batchSize: Int = minOf(512, promptTokens),
    val contextSize: Int = promptTokens + generateTokens + 64,
) {
    init {
        require(model.isNotBlank()) { "Missing the model: pass -e model <file.gguf>" }
        require(promptTokens in 1..32_768) { "pp must be in 1..32768, was $promptTokens" }
        require(generateTokens in 1..8_192) { "tg must be in 1..8192, was $generateTokens" }
        require(repetitions in 1..100) { "reps must be in 1..100, was $repetitions" }
        require(threads >= 0) { "threads must be 0 (auto) or positive, was $threads" }
        require(contextSize >= promptTokens + generateTokens + 1) {
            "ctx ($contextSize) must be larger than pp + tg (${promptTokens + generateTokens})"
        }
        require(batchSize in 1..contextSize) { "batch must be in 1..ctx, was $batchSize" }
    }

    companion object {
        private val KNOWN = setOf("model", "pp", "tg", "reps", "threads", "batch", "ctx", "class", "debug", "log")

        fun from(args: Map<String, String>): BenchmarkConfig {
            args.keys.firstOrNull { it !in KNOWN }?.let { throw IllegalArgumentException("Unknown argument: $it") }
            fun int(key: String): Int? = args[key]?.let {
                it.trim().toIntOrNull() ?: throw IllegalArgumentException("$key must be a number, was \"$it\"")
            }
            val pp = int("pp") ?: 512
            val tg = int("tg") ?: 128
            return BenchmarkConfig(
                model = args["model"].orEmpty(),
                promptTokens = pp,
                generateTokens = tg,
                repetitions = int("reps") ?: 3,
                threads = int("threads") ?: 0,
                batchSize = int("batch") ?: minOf(512, pp),
                contextSize = int("ctx") ?: (pp + tg + 64),
            )
        }
    }
}
