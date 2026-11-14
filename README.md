# llama-kotlin

[![CI](https://github.com/ThalzeronLabs/llama-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/ThalzeronLabs/llama-kotlin/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/ThalzeronLabs/llama-kotlin.svg)](https://jitpack.io/#ThalzeronLabs/llama-kotlin)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](https://developer.android.com/tools/releases/platforms#8.0)
[![API docs](https://img.shields.io/badge/docs-Dokka-7F52FF.svg)](https://thalzeronlabs.github.io/llama-kotlin/)

**Run large language models fully on-device on Android — with a small, coroutine-first Kotlin API over [llama.cpp](https://github.com/ggml-org/llama.cpp).**

No server, no API key, no internet: load a GGUF model from storage and stream tokens into your UI as a Kotlin `Flow`.
Get **guaranteed-valid JSON** out of free text, build **local semantic search** with embeddings, and **resume
conversations** from disk without re-reading the prompt.

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
    implementation("com.github.ThalzeronLabs:llama-kotlin:v0.3.0")
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
    implementation(files("libs/llama-kotlin-0.3.0.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // not pulled in by a plain AAR
}
```
</details>

<details>
<summary>Prefer the source? Include the module from a git submodule.</summary>

```bash
git submodule add https://github.com/ThalzeronLabs/llama-kotlin
git submodule update --init --recursive
```

```kotlin
// settings.gradle.kts
include(":llama")
project(":llama").projectDir = file("llama-kotlin/llama")

// app/build.gradle.kts
dependencies { implementation(project(":llama")) }
```

Your version catalog needs `android-library` (plugin), `kotlinx-coroutines-core`, `kotlinx-coroutines-test` and `junit`
entries, and the NDK and CMake versions from [Requirements](#requirements). Nothing else is required: no Dokka, no
`VERSION_NAME`. [`samples/submodule-consumer`](samples/submodule-consumer) is a complete example, compiled by CI.
</details>

## Features

| | |
|---|---|
| **Coroutine-first** | `generate()` and `chat()` return a cold `Flow<String>`. Cancel the collecting coroutine and generation stops at the next token. |
| **Structured output** | Pass a GBNF `Grammar`, or `Grammar.jsonSchema(...)`: llama.cpp's grammar sampler makes invalid output impossible, so the JSON always parses. |
| **Embeddings** | `model.embed(text)` turns text into a vector (with the model's own pooling); `cosineSimilarity()` ranks it. Semantic search with no server. |
| **Chat that remembers** | `chat()` keeps the KV cache between turns and only processes what is new: the second turn does not re-read the first. |
| **Save and resume** | `saveState(file)` / `loadState(file)` write the KV cache to disk, so a conversation survives an app restart without re-processing the prompt. |
| **Generation control** | Stop sequences, a fixed seed for reproducible answers, greedy or top-k → top-p → min-p → temperature sampling. |
| **Honest stats** | `GenerationStats`: prompt and generation tokens/s, time to first token, cached prompt tokens, why it stopped. |
| **Benchmark your phone** | A small [`benchmark`](benchmark/README.md) APK measures prompt and generation speed on a device through `adb`. |
| **Thread-safe by design** | Each session owns one native thread. Call it from any coroutine; generations never interleave. |
| **Emoji-safe streaming** | Tokens are bytes, not characters. A streaming UTF-8 decoder holds split characters back, so you never print `�`. |
| **Chat templates built in** | `chat()` formats the conversation with the template stored inside the GGUF file (Qwen, Llama, Gemma, Phi…). |
| **Fast ARM kernels** | `arm64-v8a` is compiled for ARMv8.2 dot-product + fp16 (phones from ~2019 on); `x86_64` for emulators. |
| **Tested for real** | Session logic runs against a fake engine in JVM tests; the same JNI code, built for Linux, runs against two tiny real models (generation, grammars, embeddings, saved state) on every push. |

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
        seed = 42,           // same seed + same prompt = same answer
        threads = 0,         // 0 = auto (cores - 2, max 4)
    )
)

session.generate("Once upon a time", maxTokens = 128)      // raw completion, keeps history
session.chat(messages, maxTokens = 512)                    // chat turn; reuses the cached part of `messages`
session.chat(messages, grammar = Grammar.jsonSchema(schema)) // output restricted to the schema
session.chat(messages, stop = listOf("\n\n"))             // stop sequences (not emitted)
session.chat(messages, temperature = 0.2f)                 // this answer only; the cache is kept
session.saveState(file); session.loadState(file)           // KV cache to and from disk
session.reset()                                            // forget the conversation
session.lastStats                                          // tokens/s, time to first token, stop reason…

model.embed("some text")                                   // FloatArray, normalized
model.tokenCount("some text")
```

A prompt that does not fit in what is left of the context window throws `ContextFullException`.
Full reference: **[thalzeronlabs.github.io/llama-kotlin](https://thalzeronlabs.github.io/llama-kotlin/)**.

## Structured output: free text in, a Kotlin object out

A grammar decides which tokens the model is *allowed* to write, so the answer cannot be malformed: no
"Sure! Here is your JSON:", no missing brace, no extra key. `Grammar.jsonSchema()` builds one from a JSON Schema
(objects, arrays, strings, numbers, enums, `anyOf`, `$ref`, length and item bounds — see the
[KDoc](https://thalzeronlabs.github.io/llama-kotlin/) for the exact subset). Decode it with
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), all offline:

```kotlin
@Serializable
data class Contact(val name: String, val email: String?, val phone: String?, val company: String?)

val contactSchema = Grammar.jsonSchema(
    """
    {"type": "object",
     "properties": {
       "name":    {"type": "string"},
       "email":   {"type": ["string", "null"]},
       "phone":   {"type": ["string", "null"]},
       "company": {"type": ["string", "null"]}
     },
     "required": ["name", "email", "phone", "company"]}
    """
)

suspend fun extractContact(session: LlamaSession, text: String): Contact {
    val json = session.chat(
        listOf(
            ChatMessage.system("Extract the contact details from the user's message. Use null when a field is missing."),
            ChatMessage.user(text),
        ),
        grammar = contactSchema,
    ).toList().joinToString("")
    return Json.decodeFromString<Contact>(json)   // always parses: the grammar allows nothing else
}

val contact = extractContact(session, "Hi, I'm Ana Souza from Thalzeron Labs. Write to ana@example.com.")
```

The grammar guarantees the *shape* as long as `maxTokens` leaves room for the whole answer (check
`lastStats?.stopReason`: `MAX_TOKENS` means it was cut); how well the values are filled in depends on the model. Raw GBNF works too:
`Grammar.gbnf("""root ::= "yes" | "no"""")`. Because `chat()` caches the conversation prefix, the system prompt
is processed once and reused by every later extraction on the same session.

## Embeddings and local semantic search

Load an embedding model in GGUF — e.g. `bge-small-en-v1.5` (the one this project's tests use) — and turn text into
vectors. Vectors are normalized, so cosine similarity is a plain dot product:

```kotlin
val embedder = LlamaModel.load(File(filesDir, "bge-small-en-v1.5-f16.gguf"))

val notes = listOf(
    "Buy milk, eggs and coffee",
    "Dentist appointment on Friday at 3 pm",
    "Slides for the Kotlin coroutines talk",
)
val index = notes.map { it to embedder.embed(it) }            // compute once, store anywhere (Room, a file…)

suspend fun search(query: String, top: Int = 3): List<Pair<String, Float>> {
    val q = embedder.embed(query)
    return index.map { (note, v) -> note to cosineSimilarity(q, v) }
        .sortedByDescending { it.second }
        .take(top)
}

search("when do I see the doctor?")   // notes ranked by meaning, not by shared words
```

`embed()` uses the pooling the model declares (mean, CLS or last token) and creates its context on first use
(`ModelParams.embeddingContextSize`, 512 tokens by default; longer text throws `ContextFullException`, so split it).

## Generation control and statistics

```kotlin
val session = model.newSession(SessionParams(temperature = 0.8f, seed = 1234))

session.generate("Write a haiku about Kotlin.", maxTokens = 64, stop = listOf("\n\n"))
    .collect { print(it) }

session.lastStats?.let { s ->
    println("prompt: ${s.promptTokens} tokens (${s.cachedPromptTokens} from cache), ${s.promptTokensPerSecond} t/s")
    println("answer: ${s.generatedTokens} tokens, ${s.tokensPerSecond} t/s, first token after ${s.timeToFirstTokenMillis} ms")
    println("stopped because: ${s.stopReason}")  // END_OF_GENERATION, MAX_TOKENS, STOP_SEQUENCE, CONTEXT_FULL, CANCELLED
}
```

- **Stop sequences** are matched across token boundaries and never reach your UI, even when split over tokens.
- **Seed**: the sampler is rebuilt from `seed` before every generation, so the same seed, session content and prompt
  give the same answer — in a new session or after `reset()` (on the same device and build).
- **Temperature per call**: `chat(messages, temperature = 0.2f)` or `generate(prompt, temperature = 0f)` overrides
  `SessionParams.temperature` for that call only; the next call uses the session's value again. Wire it to a
  "creativity" slider without opening a new session, so the cached conversation is not processed again.

## Save and resume a conversation

Processing a long prompt is the slow part on a phone. Save the KV cache once, restore it later — even after the
process died — and continue without paying for it again:

```kotlin
session.chat(conversation).collect { /* … */ }
session.saveState(File(filesDir, "chat-42.state"))

// Later, maybe after an app restart:
val resumed = model.newSession()
resumed.loadState(File(filesDir, "chat-42.state"))
resumed.chat(conversation + ChatMessage.user("And then?")).collect { /* … */ }
// resumed.lastStats?.cachedPromptTokens: the restored part was not processed again
```

The file uses llama.cpp's session format and holds the whole context (it grows with the conversation). It only
loads into a session of the **same model file** and library version, with a context window that is large enough;
otherwise `loadState` throws `IOException` and the session is left empty.

## Benchmark your phone

The [`benchmark`](benchmark/README.md) module is a tiny APK without a screen. Install it, push a model and run:

```bash
adb shell am instrument -w -e model qwen2.5-0.5b-instruct-q4_k_m.gguf com.thalzeronlabs.llama.benchmark/.BenchmarkInstrumentation
```

It prints a Markdown table with prompt and generation tokens/s and time to first token, plus the device, SoC,
thread count and thermal status — ready to paste into an issue.

## Tested models

| Model | File | Size | Android phone (arm64) | Linux x86_64 host check* |
|---|---|---|---|---|
| [Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) | `qwen2.5-0.5b-instruct-q4_k_m.gguf` (Q4_K_M) | 491 MB | POCO X6 Pro: see [Speed on a real phone](#speed-on-a-real-phone) | ✅ loads, chats, streams; 39–42 tokens/s on the host, not a phone (v0.1.1) |

\* The host check runs this library's real Kotlin API and JNI code, compiled for Linux instead of Android, on a
4-thread Intel Xeon @ 2.1 GHz (greedy, 128 generated tokens, 4 runs). Its output matched llama.cpp's own
`llama-simple` token for token on a test prompt. It shows the integration works — it is **not** a phone number.

The host integration tests in CI also run two tiny models on Linux on every push:
[`stories15M-q4_0`](https://huggingface.co/ggml-org/models/tree/main/tinyllamas) (generation, grammars, saved state)
and [`bge-small` f16](https://huggingface.co/ggml-org/models/tree/main/bert-bge-small) (embeddings). Those checks
are about correctness only; no speed is recorded.

### Speed on a real phone

POCO X6 Pro (MediaTek Dimensity 8300: 1× Cortex-A715 @ 3.35 GHz, 3× A715 @ 3.2 GHz, 4× A510 @ 2.2 GHz; Android 16),
Qwen2.5-0.5B-Instruct Q4_K_M, 4 threads, generation (decode) speed:

| Where the work runs | tokens/s |
|---|---|
| The four slow cores (A510), where Android tended to put the app's threads | 7.7 |
| Before this release, inside the [on-device chat app](https://github.com/ThalzeronLabs/android-on-device-llm) | 7.4–8.8 |
| The fast cores, worker threads recreated on every token (`llama-simple`) | 40.7 |
| The fast cores, persistent pinned thread pool (`llama-bench --cpu-mask 0xf0 --cpu-strict 1`) | 49.7 |
| With this release, inside the same app: one long answer | 33.3 |
| With this release, inside the same app: back-to-back answers for 5 minutes (CPU 51–71 °C, no throttling) | 31.8 → 22.3, median 27.4 |
| With this release, the app in the background (Android keeps background apps on the slow cores) | 5.3 |

The lesson: on a big.LITTLE phone the speed depends more on *which* cores run the model than on the CPU kernels.
`+i8mm` kernels did not change decode speed (34 vs 32 tok/s): decoding a small model is bound by memory bandwidth.
The same goes for SVE: the armv9 build matched the armv8.6 build on decode (45.1 vs 45.6 tok/s) and was about 11%
slower on the prompt. The app still trails the bare `llama-bench` number by about a third; profiling that gap is next.

Any GGUF chat model that the pinned llama.cpp supports should work. Bigger models that are worth a try on
phones with more RAM: Qwen2.5-1.5B-Instruct, Llama-3.2-3B-Instruct (Q4_K_M). Ran one on your phone?
[Open an issue](https://github.com/ThalzeronLabs/llama-kotlin/issues/new/choose) with the device, model,
quantization, context size, threads and `lastStats` — or simply the report of the [benchmark](benchmark/README.md) — it goes
in this table.

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

**What llama-kotlin does *not* do (yet):** GPU offload (Vulkan/OpenCL), multimodal input, LoRA adapters,
repetition penalties ([#2](https://github.com/ThalzeronLabs/llama-kotlin/issues/2), a good first issue),
32-bit ARM (`armeabi-v7a`).
If you need one of these, see the [issues](https://github.com/ThalzeronLabs/llama-kotlin/issues) — or help build it.

## How it works

```
Kotlin  LlamaModel / LlamaSession  ──Flow<String>──▶ your UI
          │  one single-thread dispatcher per session
          ▼
JNI     llama_jni.cpp   (opaque handles, Java exceptions on failure)
          ▼
C++     llama.cpp (pinned as a git submodule)  →  ggml CPU kernels (NEON on ARM)
          │  arm64: the best of ggml's Android CPU variants for this phone, loaded at start-up
          ▼
CPU     one persistent thread pool per session, pinned to the fast cores
```

The session ingests the prompt in batches (reusing the longest prefix already in the KV cache), then loops:
sample a token → feed it back → hand its bytes to Kotlin. With a grammar, the token is first sampled freely and only
checked against the grammar; the full grammar pass over the vocabulary runs only when that token is rejected (the
same trick llama.cpp uses). The Kotlin side decodes UTF-8 across token boundaries, holds back possible stop sequences,
checks for cancellation between tokens and records timing.

## Requirements

- Android 8.0+ (API 26). ABIs: `arm64-v8a` (phones) and `x86_64` (emulators).
- Building from source: JDK 17+, NDK `28.2.13676358`, CMake `4.1.2`.

```bash
git clone --recursive https://github.com/ThalzeronLabs/llama-kotlin
cd llama-kotlin
./gradlew :llama:testDebugUnitTest   # JVM unit tests
./gradlew :llama:assembleRelease     # AAR with the native libraries
scripts/host-test.sh                 # optional: the same tests against real llama.cpp and two tiny models (Linux)
```

## How I build this

I use AI assistance (Claude) while I build this project: for drafting, exploring options and writing tests. I review every change myself, each one lands through a pull request with tests, and CI must pass before anything is merged. I'm responsible for every line.

## Contributing

Contributions of every size are welcome — a test, a sampler option, a benchmark on your phone.
Start with the [`good first issue`](https://github.com/ThalzeronLabs/llama-kotlin/labels/good%20first%20issue)
list and read [CONTRIBUTING.md](CONTRIBUTING.md). Changes are tracked in [CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0 © Esdras Souza (Thalzeron Labs). llama.cpp is MIT-licensed by its authors (see [`NOTICE`](NOTICE)).

The release builds bundle third-party code; their licenses and notices are in [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt), which is also attached to every release and packed inside each build: `assets/llama-kotlin/THIRD-PARTY-NOTICES.txt` in the AAR (its own path, so an app's own notices file never replaces it) and `assets/THIRD-PARTY-NOTICES.txt` in the benchmark APK.
