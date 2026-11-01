# Module llama-kotlin

Run large language models fully on-device on Android with a small, coroutine-first Kotlin API over
[llama.cpp](https://github.com/ggml-org/llama.cpp).

Start with [com.thalzeronlabs.llama.LlamaModel.Companion.load], open a [com.thalzeronlabs.llama.LlamaSession] with
[com.thalzeronlabs.llama.LlamaModel.newSession] and collect the `Flow<String>` returned by
[com.thalzeronlabs.llama.LlamaSession.chat] or [com.thalzeronlabs.llama.LlamaSession.generate].

# Package com.thalzeronlabs.llama

The whole public API: models, sessions, parameters, chat messages and generation statistics.
