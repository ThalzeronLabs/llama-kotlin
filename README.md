# llama-kotlin

**Run large language models fully on-device on Android — with a small, coroutine-first Kotlin API over [llama.cpp](https://github.com/ggml-org/llama.cpp).**

No server, no API key, no internet: load a GGUF model from storage and stream tokens into your UI as a Kotlin `Flow`.

```kotlin
val model = LlamaModel.load(File(filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"))

model.newSession().use { session ->
    session.chat(
        listOf(
            ChatMessage.system("You are a concise assistant."),
            ChatMessage.user("Explain coroutines in one sentence."),
        )
    ).collect { piece -> print(piece) }   // streamed, token by token

    println("\n${session.lastStats?.tokensPerSecond} tokens/s")
}

model.close()
```

## Why this library

| | |
|---|---|
| **Coroutine-first** | `generate()` and `chat()` return a cold `Flow<String>`. Cancel the collecting coroutine and generation stops at the next token. |
| **Thread-safe by design** | Each session owns one native thread. Call it from any coroutine; generations never interleave. |
| **Emoji-safe streaming** | Tokens are bytes, not characters. A streaming UTF-8 decoder holds split characters back, so you never print `�`. |
| **Chat templates built in** | `chat()` formats the conversation with the template stored inside the GGUF file (Qwen, Llama, Gemma, Phi…). |
| **No leaks** | Models and sessions are `Closeable`; closing a model closes its sessions. |
| **Tested** | The session logic (streaming, limits, cancellation, stats) runs against a fake engine in plain JVM unit tests. |

## API at a glance

```kotlin
// Load once (on Dispatchers.IO), share across screens.
val model = LlamaModel.load(file, ModelParams(useMmap = true))

// One session = one conversation with its own context window.
val session = model.newSession(
    SessionParams(
        contextSize = 2048,  // prompt + answer + history, in tokens
        temperature = 0.7f,  // 0 = greedy
        topK = 40, topP = 0.95f, minP = 0.05f,
        threads = 0,         // 0 = auto (cores - 2, max 4)
    )
)

session.generate("Once upon a time", maxTokens = 128)   // raw completion, keeps history
session.chat(messages, maxTokens = 512)                 // chat turn, stateless
session.reset()                                         // forget the conversation
session.usedTokens(); session.contextSize()
session.lastStats                                       // prompt tokens, generated tokens, tokens/s
```

A prompt that does not fit in what is left of the context window throws `ContextFullException`.

## Choosing a model

Any GGUF chat model that llama.cpp supports works. On a mid-range phone, start small and quantized:

| Model | Size (Q4_K_M) | Good for |
|---|---|---|
| Qwen2.5 0.5B Instruct | ~0.4 GB | fast replies, simple tasks |
| Qwen2.5 1.5B Instruct | ~1.0 GB | better answers, still quick |
| Llama 3.2 3B Instruct | ~2.0 GB | best quality on 8 GB+ phones |

Download the model at runtime (or let the user pick a file) — do not ship it inside the APK.

## Setup

```bash
git clone --recursive https://github.com/ThalzeronLabs/llama-kotlin
```

Add the module to your project (`settings.gradle.kts`):

```kotlin
includeBuild("path/to/llama-kotlin")   // or include(":llama") with projectDir
```

Requirements: Android 8.0+ (API 26), NDK 28, CMake 3.22+. ABIs: `arm64-v8a` (phones) and `x86_64` (emulators).

## How it works

```
Kotlin  LlamaModel / LlamaSession  ──Flow<String>──▶ your UI
          │  one single-thread dispatcher per session
          ▼
JNI     llama_jni.cpp   (opaque handles, Java exceptions on failure)
          ▼
C++     llama.cpp (pinned as a git submodule)  →  ggml CPU kernels (NEON on ARM)
```

The session ingests the prompt in batches, then loops: sample a token → feed it back → hand its bytes to Kotlin.
The Kotlin side decodes UTF-8 across token boundaries, checks for cancellation between tokens and records timing.

## Tests

```bash
./gradlew :llama:testDebugUnitTest
```

## License

Apache-2.0 © Esdras Souza (Thalzeron Labs). llama.cpp is MIT-licensed by its authors (see `NOTICE`).
