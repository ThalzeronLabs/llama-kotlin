# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0, minor versions may change the public API.

## [Unreleased]

## [0.2.1] - 2026-11-02

### Added

- `temperature` parameter on `chat()` and `generate()`: overrides `SessionParams.temperature` for that call only
  (`null`, the default, keeps the session's value). Changing it keeps the KV cache, so an app no longer has to open a
  new session (and re-process the conversation) when the user changes the temperature. Closes #10.

## [0.2.0] - 2026-11-02

### Added

- Structured output: `Grammar.gbnf(...)` for any GBNF grammar and `Grammar.jsonSchema(...)` to restrict the output to
  JSON valid against a JSON Schema (objects, arrays, primitives, `enum`, `const`, `anyOf`/`oneOf`, local `$ref`s,
  length and item bounds); `Grammar.json()` and `Grammar.jsonObject()` for any JSON. Pass `grammar = ...` to
  `generate()` or `chat()`. The grammar is checked lazily (sample first, then verify), like llama.cpp's own sampler.
- Embeddings: `LlamaModel.embed(text, normalize = true)`, `LlamaModel.embeddingSize`, and `cosineSimilarity(a, b)`.
  The embedding context is created on first use (`ModelParams.embeddingContextSize`, `ModelParams.embeddingThreads`).
- Stop sequences: `stop = listOf(...)` on `generate()` and `chat()`, matched across token boundaries and never emitted.
- `chat()` keeps the KV cache between calls and only processes the part of the conversation it has not seen.
- `LlamaSession.saveState(file)` / `loadState(file)`: save the KV cache to disk and resume a conversation later
  without processing the prompt again (llama.cpp's session file format).
- `GenerationStats`: `promptMillis`, `promptTokensPerSecond`, `timeToFirstTokenMillis`, `cachedPromptTokens` and
  `stopReason` (`StopReason`).
- `LlamaModel.tokenCount(text)`.
- `benchmark` module: an APK without a screen, run with `adb shell am instrument`, that measures prompt and
  generation speed and time to first token on a device and prints a Markdown report. Attached to GitHub releases.
- Host integration tests: the JNI code also builds for the desktop JVM, and `scripts/host-test.sh` (run in CI) tests
  it against two tiny real models, plus every `Grammar.jsonSchema` output against llama.cpp's GBNF validator.

### Changed

- The sampler is rebuilt from the session's seed before every generation, so the same seed, session content and
  prompt give the same answer (before, the random state carried over from one generation to the next).
- `chat()` no longer resets the session before each call; it cuts the cache where the conversation differs.
- `GenerationStats` times come from a monotonic clock instead of the wall clock.
- `generate()` and `chat()` gained parameters (`grammar`, `stop`): source compatible, but code compiled against
  0.1.x must be recompiled.

### Fixed

- Prompts with emoji or other characters outside the Basic Multilingual Plane reached the tokenizer as
  *modified* UTF-8 (surrogate pairs); they are now passed as standard UTF-8.

## [0.1.1] - 2026-11-01

### Fixed

- JitPack build. JitPack's image has an older glibc than the Android SDK's CMake 4.1.2 binary needs, so the
  `v0.1.0` build failed before compiling any C++. `jitpack.yml` now uses the official Kitware CMake 4.1.2, which
  runs there. Install with `com.github.ThalzeronLabs:llama-kotlin:v0.1.1`. The library code is unchanged from 0.1.0.
- Release notes no longer include the CHANGELOG's link references.

## [0.1.0] - 2026-11-01

First public release.

### Added

- `LlamaModel.load(file)`: loads a GGUF model on `Dispatchers.IO`, memory-mapped by default (`ModelParams.useMmap`).
- `LlamaModel.newSession(SessionParams)`: one conversation with its own context window, running on its own native thread.
- `LlamaSession.generate(prompt)` and `LlamaSession.chat(messages)` return a cold `Flow<String>`; cancelling the
  collector stops generation at the next token.
- Chat formatting with the template stored in the GGUF file (`LlamaModel.formatChat`).
- Sampling: greedy (`temperature = 0`) or top-k → top-p → min-p → temperature → seeded random.
- Streaming UTF-8 decoder that never emits a broken character when a token ends in the middle of one.
- `GenerationStats` (prompt tokens, generated tokens, tokens per second) and `ContextFullException`.
- Native builds for `arm64-v8a` (with ARMv8.2 dot-product and fp16 kernels) and `x86_64` (emulators).
- Distribution through JitPack (`com.github.ThalzeronLabs:llama-kotlin:v0.1.0`) and as an AAR attached to the
  GitHub release.
- API reference generated with Dokka and published to GitHub Pages.
- Contributor guide, code of conduct, issue and pull request templates.
- `kotlinx-coroutines-core` is exposed as an `api` dependency, because `Flow` is part of the public API.

[Unreleased]: https://github.com/ThalzeronLabs/llama-kotlin/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/ThalzeronLabs/llama-kotlin/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/ThalzeronLabs/llama-kotlin/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/ThalzeronLabs/llama-kotlin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ThalzeronLabs/llama-kotlin/releases/tag/v0.1.0
