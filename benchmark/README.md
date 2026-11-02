# llama-kotlin benchmark

A small command-line benchmark that runs **on your phone** and measures, through the same public API an app uses:

- **pp** — prompt processing speed (tokens per second),
- **tg** — generation speed (tokens per second),
- **TTFT** — time to first token (prompt processing included).

It has no screen: you start it from a computer with `adb`, it prints progress and a Markdown table in the terminal.

> No numbers are published here on purpose: this project only lists results that someone measured on a real
> device. Run it on yours and [share the table](#share-your-numbers).

## Run it

You need `adb` ([Android platform-tools](https://developer.android.com/tools/releases/platform-tools)), a phone with
USB debugging on, and a GGUF model, e.g. [Qwen2.5-0.5B-Instruct Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF).

**1. Install the APK.** Download `llama-kotlin-benchmark-<version>.apk` from the
[latest release](https://github.com/ThalzeronLabs/llama-kotlin/releases/latest), or build it:

```bash
./gradlew :benchmark:assembleRelease
```

```bash
adb install -r benchmark/build/outputs/apk/release/benchmark-release.apk
```

(The APK is signed with a throwaway debug key. If `adb install` reports a signature mismatch, uninstall the old one
first: `adb uninstall com.thalzeronlabs.llama.benchmark`.)

**2. Copy the model into the app's folder** (the app can read it there without any permission):

```bash
adb shell mkdir -p /sdcard/Android/data/com.thalzeronlabs.llama.benchmark/files
```

```bash
adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.thalzeronlabs.llama.benchmark/files/
```

**3. Run it:**

```bash
adb shell am instrument -w -e model qwen2.5-0.5b-instruct-q4_k_m.gguf com.thalzeronlabs.llama.benchmark/.BenchmarkInstrumentation
```

It loads the model, does one warm-up run, then the measured runs, and prints something like this (the values
below are placeholders, not measurements):

```
| Device | SoC | Model | Threads | pp512 (t/s) | tg128 (t/s) | TTFT (ms) |
|---|---|---|---|---|---|---|
| <maker model> | <soc> | qwen2.5-0.5b-instruct-q4_k_m.gguf | auto | <mean ± sd> | <mean ± sd> | <mean ± sd> |
```

followed by the details (repetitions, prompt and generated tokens, context, model size, ABI, cores, Android version,
thermal status before and after, library version). The same text is saved on the phone; fetch it with:

```bash
adb pull /sdcard/Android/data/com.thalzeronlabs.llama.benchmark/files/benchmark-results.md
```

## Options

Pass them as more `-e key value` pairs, before the component name:

| Key | Default | Meaning |
|---|---|---|
| `model` | (required) | GGUF file: a name in the app's folder (step 2) or an absolute path the app can read |
| `pp` | `512` | prompt tokens to process |
| `tg` | `128` | tokens to generate |
| `reps` | `3` | measured repetitions, after one warm-up run |
| `threads` | `0` | CPU threads; `0` = the library default (cores − 2, at most 4) |
| `batch` | `min(512, pp)` | prompt batch size |
| `ctx` | `pp + tg + 64` | context window |

Example — a short prompt, longer generation, 4 threads, 5 runs:

```bash
adb shell am instrument -w -e model qwen2.5-0.5b-instruct-q4_k_m.gguf -e pp 128 -e tg 256 -e threads 4 -e reps 5 com.thalzeronlabs.llama.benchmark/.BenchmarkInstrumentation
```

## How it measures

- Model loaded with the default `ModelParams` (memory-mapped); greedy sampling (`temperature = 0`).
- The prompt is plain English text, cut to the largest number of whole words that fits in `pp` tokens; the table
  shows the real token count.
- Each run resets the session and calls `generate(prompt, maxTokens = tg)`. Speeds come from `GenerationStats`:
  pp = prompt tokens ÷ `promptMillis`, tg = generated tokens ÷ `generationMillis`, TTFT = `timeToFirstTokenMillis`.
- The model may end its answer before `tg` tokens; the details list the real count per run.
- Generation runs right after a `pp`-token prompt, so tg is measured at that context depth (unlike `llama-bench`,
  which measures tg on an empty context). Numbers from the two tools are not directly comparable.

For fair numbers: charge the phone, close other apps, let it cool down between runs, keep the screen on. Phones slow
down when they get hot, which is why the thermal status is part of the report.

## Share your numbers

Open an issue with the whole printed report (table and details):
[ThalzeronLabs/llama-kotlin issues](https://github.com/ThalzeronLabs/llama-kotlin/issues/new/choose). Results go in
the "Tested models" table of the main README, credited to you.
