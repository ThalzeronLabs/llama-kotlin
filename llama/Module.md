# Module llama-kotlin

Run large language models fully on-device on Android with a small, coroutine-first Kotlin API over
[llama.cpp](https://github.com/ggml-org/llama.cpp).

Start with [com.thalzeronlabs.llama.LlamaModel.Companion.load], open a [com.thalzeronlabs.llama.LlamaSession] with
[com.thalzeronlabs.llama.LlamaModel.newSession] and collect the `Flow<String>` returned by
[com.thalzeronlabs.llama.LlamaSession.chat] or [com.thalzeronlabs.llama.LlamaSession.generate].

- Structured output: pass a [com.thalzeronlabs.llama.Grammar] (GBNF, or
  [com.thalzeronlabs.llama.Grammar.Companion.jsonSchema] for JSON that follows a schema).
- Embeddings: [com.thalzeronlabs.llama.LlamaModel.embed] and [com.thalzeronlabs.llama.cosineSimilarity].
- Resuming a conversation: [com.thalzeronlabs.llama.LlamaSession.saveState] and
  [com.thalzeronlabs.llama.LlamaSession.loadState].
- Numbers about a generation: [com.thalzeronlabs.llama.GenerationStats] in
  [com.thalzeronlabs.llama.LlamaSession.lastStats].

# Package com.thalzeronlabs.llama

The whole public API: models, sessions, grammars, embeddings, parameters, chat messages and generation statistics.
