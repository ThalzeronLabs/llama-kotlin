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

## How the code is organized

| Path | What lives there |
|---|---|
| `llama/src/main/kotlin/.../LlamaModel.kt` | Loading a GGUF file, opening sessions, chat templates |
| `llama/src/main/kotlin/.../LlamaSession.kt` | Streaming, cancellation, limits, statistics (pure Kotlin, unit-tested) |
| `llama/src/main/kotlin/.../Params.kt` | `ModelParams`, `SessionParams`, `ChatMessage`, `GenerationStats` |
| `llama/src/main/kotlin/.../Engine.kt` | The small interface between the session and the native code (faked in tests) |
| `llama/src/main/kotlin/.../NativeBridge.kt` | The `external` JNI declarations |
| `llama/src/main/cpp/llama_jni.cpp` | The JNI implementation on top of llama.cpp |
| `third_party/llama.cpp` | llama.cpp, pinned as a git submodule |

The session logic talks to an `Engine` interface, so most behaviour can be tested on the JVM with a fake engine
(see `LlamaSessionTest`). Prefer adding a test there over anything that needs a device.

## Pull requests

1. Fork, create a branch from `main`, keep the change small (one idea per PR).
2. Add or update tests when you change behaviour. `./gradlew :llama:testDebugUnitTest` must pass.
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
context size and the thread count. Numbers that were not measured stay as *to be measured*.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By taking part you agree to uphold it.
