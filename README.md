# llama-kotlin

[![CI](https://github.com/ThalzeronLabs/llama-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/ThalzeronLabs/llama-kotlin/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/ThalzeronLabs/llama-kotlin.svg)](https://jitpack.io/#ThalzeronLabs/llama-kotlin)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](https://developer.android.com/tools/releases/platforms#8.0)
[![API docs](https://img.shields.io/badge/docs-Dokka-7F52FF.svg)](https://thalzeronlabs.github.io/llama-kotlin/)

**Run large language models fully on-device on Android — with a small, coroutine-first Kotlin API over [llama.cpp](https://github.com/ggml-org/llama.cpp).**

No server, no API key, no internet: load a GGUF model from storage and stream tokens into your UI as a Kotlin `Flow`.

## Quick start

**1. Add the dependency** (JitPack builds the native code from this repository's tag):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.ThalzeronLabs:llama-kotlin:v0.1.1")
}
```

**2. Chat with a model** (any GGUF file on the device, e.g. [Qwen2.5-0.5B-Instruct Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF)):

```kotlin
lifecycleScope.launch {
    val model = LlamaModel.load(File(filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"))
    model.newSession().use { session ->
        session.chat(listOf(ChatMessage.user("Explain coroutines in one sentence.")))
            .collect { piece -> textView.append(piece) }   // streamed, token by token
        Log.i("llama", "${session.lastStats?.tokensPerSecond} tokens/s")
    }
    model.close()
}
```

That's it. Download the model at runtime (or let the user pick a file) — do not ship it inside the APK.

<details>
<summary>No JitPack? Use the AAR from the release.</summary>

Every [GitHub release](https://github.com/ThalzeronLabs/llama-kotlin/releases) has the AAR attached
(`llama-kotlin-<version>.aar`, with its SHA-256). Put it in `app/libs/` and:

```kotlin
dependencies {
    implementation(files("libs/llama-kotlin-0.1.1.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // not pulled in by a plain AAR
}
```
</details>

## Features

| | |
|---|---|
| **Coroutine-first** | `generate()` and `chat()` return a cold `Flow<String>`. Cancel the collecting coroutine and generation stops at the next token. |
| **Thread-safe by design** | Each session owns one native thread. Call it from any coroutine; generations never interleave. |
| **Emoji-safe streaming** | Tokens are bytes, not characters. A streaming UTF-8 decoder holds split characters back, so you never print `�`. |
| **Chat templates built in** | `chat()` formats the conversation with the template stored inside the GGUF file (Qwen, Llama, Gemma, Phi…). |
| **Sampling** | Greedy (`temperature = 0`) or top-k → top-p → min-p → temperature with a fixed seed. |
| **Fast ARM kernels** | `arm64-v8a` is compiled for ARMv8.2 dot-product + fp16 (phones from ~2019 on); `x86_64` for emulators. |
| **No leaks** | Models and sessions are `Closeable`; closing a model closes its sessions. |
| **Tested** | The session logic (streaming, limits, cancellation, stats) runs against a fake engine in plain JVM unit tests on every push. |

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
Full reference: **[thalzeronlabs.github.io/llama-kotlin](https://thalzeronlabs.github.io/llama-kotlin/)**.

## Tested models

| Model | File | Size | Android phone (arm64) | Linux x86_64 host check* |
|---|---|---|---|---|
| [Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) | `qwen2.5-0.5b-instruct-q4_k_m.gguf` (Q4_K_M) | 491 MB | tokens/s **to be measured** | ✅ loads, chats, streams; 39–42 tokens/s |

\* The host check runs this library's real Kotlin API and JNI code, compiled for Linux instead of Android, on a
4-thread Intel Xeon @ 2.1 GHz (greedy, 128 generated tokens, 4 runs). Its output matched llama.cpp's own
`llama-simple` token for token on a test prompt. It shows the integration works — it is **not** a phone number.

Any GGUF chat model that the pinned llama.cpp supports should work. Bigger models that are worth a try on
phones with more RAM: Qwen2.5-1.5B-Instruct, Llama-3.2-3B-Instruct (Q4_K_M). Ran one on your phone?
[Open an issue](https://github.com/ThalzeronLabs/llama-kotlin/issues/new/choose) with the device, model,
quantization, context size, threads and `lastStats` — it goes in this table.

## How it compares

There are several good ways to run an LLM on Android. Pick the one that fits:

| | llama-kotlin | llama.cpp's own Android example | MediaPipe / LiteRT LLM Inference | Gemini Nano (ML Kit GenAI / AICore) |
|---|---|---|---|---|
| Install | One Gradle line (JitPack) or an AAR | Copy the sample module into your project | Gradle dependency (Google Maven) | Gradle dependency, model ships with the OS |
| Models | Any GGUF that llama.cpp supports | Any GGUF | Models converted to Google's formats | Gemini Nano only |
| Hardware | CPU only (NEON / dot-product) | CPU by default; other llama.cpp backends need your own build setup | CPU and GPU | On-device accelerators via AICore |
| Devices | Any arm64 / x86_64 device, API 26+ | Whatever you build for | See Google's device requirements | Only supported devices (mostly recent flagships) |
| API style | `Flow<String>`, `Closeable`, coroutines | Sample code you own and maintain | Google's inference API | Google's GenAI API |
| Maturity | **0.x — new, small, API may change** | Maintained upstream | Google-maintained | Google-maintained |

**What llama-kotlin does *not* do (yet):** GPU offload (Vulkan/OpenCL), embeddings, grammar / JSON-constrained
output, multimodal input, LoRA adapters, saving/restoring the KV cache, 32-bit ARM (`armeabi-v7a`).
If you need one of these, see the [issues](https://github.com/ThalzeronLabs/llama-kotlin/issues) — or help build it.

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

## Requirements

- Android 8.0+ (API 26). ABIs: `arm64-v8a` (phones) and `x86_64` (emulators).
- Building from source: JDK 17+, NDK `28.2.13676358`, CMake `4.1.2`.

```bash
git clone --recursive https://github.com/ThalzeronLabs/llama-kotlin
cd llama-kotlin
./gradlew :llama:testDebugUnitTest   # JVM unit tests
./gradlew :llama:assembleRelease     # AAR with the native libraries
```

## Contributing

Contributions of every size are welcome — a test, a sampler option, a benchmark on your phone.
Start with the [`good first issue`](https://github.com/ThalzeronLabs/llama-kotlin/labels/good%20first%20issue)
list and read [CONTRIBUTING.md](CONTRIBUTING.md). Changes are tracked in [CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0 © Esdras Souza (Thalzeron Labs). llama.cpp is MIT-licensed by its authors (see [`NOTICE`](NOTICE)).
