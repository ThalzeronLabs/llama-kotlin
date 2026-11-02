#!/usr/bin/env bash
# Runs every test against the real llama.cpp, on this computer instead of a phone:
#   1. builds the JNI library (llama/src/main/cpp) for the desktop JVM,
#   2. builds llama.cpp's GBNF validator (checks the grammars produced by Grammar.jsonSchema),
#   3. downloads two tiny GGUF models (~86 MB, checked by SHA-256): tinyllamas stories15M and bge-small,
#   4. runs the JVM tests with them, so the host-only tests are not skipped.
#
# Usage: scripts/host-test.sh            (Linux; needs CMake, a C++17 compiler, curl and a JDK 17+)
# Speeds measured this way are a desktop CPU's, never a phone's: do not report them as Android numbers.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${HOST_TEST_DIR:-$ROOT/build-host}"
MODELS="${HOST_TEST_MODELS:-$OUT/models}"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
LLAMA="$ROOT/third_party/llama.cpp"

if [ ! -f "$LLAMA/include/llama.h" ]; then
  git -C "$ROOT" submodule update --init --recursive
fi

echo "==> Building the JNI library for the host"
cmake -S "$ROOT/llama/src/main/cpp" -B "$OUT" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF \
  -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF > /dev/null
cmake --build "$OUT" --target llama_kotlin -j "$JOBS"

echo "==> Building llama.cpp's GBNF validator"
"${CXX:-c++}" -std=c++17 -O1 -I"$LLAMA/include" -I"$LLAMA/ggml/include" \
  "$LLAMA/tests/test-gbnf-validator.cpp" -o "$OUT/gbnf-validator" \
  "$OUT/build-llama/src/libllama.a" "$OUT/build-llama/ggml/src/libggml.a" \
  "$OUT/build-llama/ggml/src/libggml-cpu.a" "$OUT/build-llama/ggml/src/libggml-base.a" -lpthread -ldl

echo "==> Downloading the test models"
mkdir -p "$MODELS"
download() { # url file sha256
  if [ ! -f "$MODELS/$2" ] || ! echo "$3  $MODELS/$2" | sha256sum -c --status; then
    curl -sSfL --retry 3 -o "$MODELS/$2" "$1"
    echo "$3  $MODELS/$2" | sha256sum -c --quiet
  fi
}
download https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories15M-q4_0.gguf \
  stories15M-q4_0.gguf 66967fbece6dbe97886593fdbb73589584927e29119ec31f08090732d1861739
download https://huggingface.co/ggml-org/models/resolve/main/bert-bge-small/ggml-model-f16.gguf \
  bge-small-f16.gguf f0b2fef971e8366438bfd2d9aefea1b0115919389448806d290237f638bae999

echo "==> Running the tests against the real library"
cd "$ROOT"
./gradlew :llama:testDebugUnitTest :benchmark:testDebugUnitTest --rerun \
  -Pllama.nativeLibDir="$OUT" \
  -Pllama.testModel="$MODELS/stories15M-q4_0.gguf" \
  -Pllama.testEmbeddingModel="$MODELS/bge-small-f16.gguf" \
  -Pllama.gbnfValidator="$OUT/gbnf-validator"

# Fail loudly if the host tests were skipped (for example, a property did not reach the test JVM).
if grep -q '<skipped' llama/build/test-results/testDebugUnitTest/TEST-com.thalzeronlabs.llama.NativeIntegrationTest.xml; then
  echo "NativeIntegrationTest was skipped" >&2
  exit 1
fi
echo "==> All host tests passed"
