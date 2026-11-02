# Contributing to llama-kotlin

Thanks for taking the time! Small, focused contributions are the most welcome kind: a new sampler option,
a test, a clearer doc comment, a sample, a benchmark on your phone.

## Find something to work on

- Issues labelled [`good first issue`](https://github.com/ThalzeronLabs/llama-kotlin/labels/good%20first%20issue)
  are small and self-contained, with the files to touch listed in the issue.
- Issues labelled [`help wanted`](https://github.com/ThalzeronLabs/llama-kotlin/labels/help%20wanted) need
  something the maintainer cannot easily do alone (a specific device, a benchmark, a design opinion).
- Have an idea that is not in the tracker? Open an issue first so we can agree on the shape before you write code.

Comment on the issue to say you are taking it, so nobody duplicates the work.

## Set up

Requirements: JDK 17+, Android SDK (API 37), NDK `28.2.13676358`, CMake `4.1.2`.
Android Studio installs the NDK and CMake from *SDK Manager → SDK Tools*.

```bash
git clone --recursive https://github.com/ThalzeronLabs/llama-kotlin
cd llama-kotlin
./gradlew :llama:testDebugUnitTest      # fast: JVM unit tests, no device, no model
./gradlew :llama:assembleRelease        # full build: compiles llama.cpp for arm64-v8a and x86_64
./gradlew :llama:dokkaGenerate          # API docs in llama/build/dokka/html
```

Forgot `--recursive`? Run `git submodule update --init --recursive`.

### Host integration tests (real llama.cpp, no phone)

The JNI code also compiles for the desktop JVM. One script builds it, downloads two tiny models (~86 MB,
checked by SHA-256) and runs every test against the real library, including the ones that are skipped otherwise:

```bash
scripts/host-test.sh
```

It needs Linux with CMake, a C++17 compiler and curl (macOS is untested). CI runs it on every push. Touched the
native code, the grammar converter or the session logic? Run it before opening the PR. The models are
[`stories15M-q4_0`](https://huggingface.co/ggml-org/models/tree/main/tinyllamas) (a 15M-parameter story model with no
chat template) and [`bge-small` f16](https://huggingface.co/ggml-org/models/tree/main/bert-bge-small) (embeddings).
Speeds on a desktop CPU say nothing about phones: never report them as Android numbers.

## How the code is organized

| Path | What lives there |
|---|---|
| `llama/src/main/kotlin/.../LlamaModel.kt` | Loading a GGUF file, opening sessions, chat templates |
| `llama/src/main/kotlin/.../LlamaSession.kt` | Streaming, cancellation, limits, stop sequences, saved state, statistics (pure Kotlin, unit-tested) |
| `llama/src/main/kotlin/.../Params.kt` | `ModelParams`, `SessionParams`, `ChatMessage`, `GenerationStats`, `StopReason` |
| `llama/src/main/kotlin/.../Grammar.kt`, `JsonSchemaGrammar.kt`, `Json.kt` | GBNF grammars and the JSON Schema → GBNF converter |
| `llama/src/main/kotlin/.../Embeddings.kt` | `cosineSimilarity` and the embedding runner behind `LlamaModel.embed` |
| `llama/src/main/kotlin/.../StopSequenceMatcher.kt` | Stop sequences across token boundaries |
| `llama/src/main/kotlin/.../Engine.kt` | The small interface between the session and the native code (faked in tests) |
| `llama/src/main/kotlin/.../NativeBridge.kt` | The `external` JNI declarations |
| `llama/src/main/cpp/llama_jni.cpp` | The JNI implementation on top of llama.cpp |
| `benchmark/` | The on-device benchmark APK (`adb shell am instrument`), see [benchmark/README.md](benchmark/README.md) |
| `scripts/host-test.sh` | Builds the JNI code for the desktop and runs the tests against real models |
| `third_party/llama.cpp` | llama.cpp, pinned as a git submodule |

The session logic talks to an `Engine` interface, so most behaviour can be tested on the JVM with a fake engine
(see `LlamaSessionTest`). Prefer adding a test there over anything that needs a device. Behaviour that only the
real library can show (sampling, grammars, embeddings, saved state) goes in `NativeIntegrationTest`.

## Pull requests

1. Fork, create a branch from `main`, keep the change small (one idea per PR).
2. Add or update tests when you change behaviour. `./gradlew :llama:testDebugUnitTest :benchmark:testDebugUnitTest`
   must pass (and `scripts/host-test.sh` if you touched native code).
3. Public API change? Update the KDoc, the README if it shows that API, and add a line under
   `## [Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md).
4. Write commit messages in the imperative mood ("Add XTC sampler", not "Added…").
5. Open the PR and fill in the template. CI runs the unit tests and builds the AAR.

PRs are merged with *squash and merge*, so the PR title becomes the commit message on `main`.

## Style

- Kotlin official code style (`kotlin.code.style=official`), 4 spaces, 120 columns.
- Every public declaration has a KDoc comment: it ends up in the [API reference](https://thalzeronlabs.github.io/llama-kotlin/).
- No new runtime dependencies without discussing it in an issue first. The library should stay small.
- Do not commit models (`*.gguf`), keys or `local.properties`.

## Numbers and claims

If you add a benchmark or a "tested on" entry, include the device, the model file, the quantization, the
context size and the thread count — the [benchmark](benchmark/README.md) report has all of it. Numbers that were not
measured stay as *to be measured*.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By taking part you agree to uphold it.
