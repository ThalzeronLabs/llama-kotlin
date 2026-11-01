# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0, minor versions may change the public API.

## [Unreleased]

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

[Unreleased]: https://github.com/ThalzeronLabs/llama-kotlin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ThalzeronLabs/llama-kotlin/releases/tag/v0.1.0
