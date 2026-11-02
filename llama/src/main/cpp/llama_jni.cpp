// JNI bridge between Kotlin and llama.cpp.
//
// Ownership model: Kotlin holds opaque `long` handles (a llama_model*, a Session* or an Embedder*) and is
// responsible for calling the matching free function exactly once. A Session or an Embedder is never used from
// two threads at the same time: the Kotlin side confines each one (a dispatcher thread or a mutex).
//
// The file also builds on a desktop JVM (Linux/macOS) so the host integration tests can run it against a real
// model; only logging differs.

#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"

#define LOG_TAG "llama-kotlin"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, LOG_TAG ": " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#endif

namespace {

// Sampling settings of a session. The sampler chain is rebuilt from them before every generation, so each
// generation starts from the same random state: same seed + same context + same prompt = same answer.
struct SamplingConfig {
    float    temperature      = 0.7f;
    int32_t  top_k            = 40;
    float    top_p            = 0.95f;
    float    min_p            = 0.05f;
    uint32_t seed             = 42;
};

struct Session {
    llama_context *     ctx     = nullptr;
    ggml_threadpool *   pool    = nullptr; // persistent worker threads on the fast cores (see make_pool())
    llama_sampler *     chain   = nullptr; // everything but the grammar
    llama_sampler *     grammar = nullptr; // optional, applied lazily (see sample())
    llama_batch_ext *   batch   = nullptr;
    const llama_vocab * vocab   = nullptr;
    int32_t             n_batch = 0;
    SamplingConfig      sampling;
    // Every token currently held in the KV cache, in order. Its size is the next position.
    std::vector<llama_token> history;
    std::vector<llama_token_data> candidates;
};

struct Embedder {
    llama_context *     ctx    = nullptr;
    llama_batch_ext *   batch  = nullptr;
    const llama_vocab * vocab  = nullptr;
    int32_t             n_embd = 0;
};

std::once_flag g_backend_once;

void throw_java(JNIEnv * env, const char * cls, const std::string & msg) {
    jclass ex = env->FindClass(cls);
    if (ex != nullptr) {
        env->ThrowNew(ex, msg.c_str());
    }
}

void throw_state(JNIEnv * env, const std::string & msg) {
    throw_java(env, "java/lang/IllegalStateException", msg);
}

void throw_context_full(JNIEnv * env, size_t needed, int32_t left) {
    throw_java(env, "com/thalzeronlabs/llama/ContextFullException",
               "Prompt needs " + std::to_string(needed) + " tokens but only " + std::to_string(std::max(left, 0)) +
               " are left in the context window.");
}

// Reads a Java String as real UTF-8. GetStringUTFChars returns *modified* UTF-8, which encodes emoji as two
// 3-byte surrogates that the tokenizer would not recognize, so the bytes come from String.getBytes("UTF-8").
std::string to_std_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    jclass string_class = env->GetObjectClass(s);
    jmethodID get_bytes = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(s, get_bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes);
    return out;
}

// Builds a Java String from raw UTF-8 bytes. NewStringUTF expects *modified* UTF-8 and would choke on
// emoji (4-byte sequences), so the bytes go through java.lang.String(byte[], "UTF-8") instead.
jstring utf8_to_jstring(JNIEnv * env, const std::string & text) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return result;
}

// The cores worth computing on. Phones mix a slow cluster with fast ones, and Android tends to run an app's worker
// threads on the slow one: on a Dimensity 8300 the same model decodes at 7.7 tok/s on the four Cortex-A510s and at
// 40+ on the Cortex-A715s. Returns the cores whose maximum frequency is above the slowest cluster's, or every core
// when they are all alike or the frequencies cannot be read (desktop, emulator).
const std::vector<int> & fast_cores() {
    static const std::vector<int> cores = [] {
        const int n = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
        std::vector<long> freq(n, 0);
        for (int i = 0; i < n; ++i) {
            std::ifstream in("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
            if (!(in >> freq[i])) freq[i] = 0;
        }
        std::vector<int> all(n);
        for (int i = 0; i < n; ++i) all[i] = i;
        if (std::any_of(freq.begin(), freq.end(), [](long f) { return f <= 0; })) return all;
        const long slowest = *std::min_element(freq.begin(), freq.end());
        std::vector<int> fast;
        for (int i = 0; i < n; ++i) {
            if (freq[i] > slowest) fast.push_back(i);
        }
        return fast.empty() ? all : fast;
    }();
    return cores;
}

// On arm64 Android the CPU backend comes as one library per feature level (see CMakeLists.txt). Asks each one how
// well it fits this CPU (its ggml_backend_score() only checks the CPU features, it runs no vector code) and loads the
// best, so a phone without dot-product gets the plain armv8.0 code instead of crashing with SIGILL. The libraries
// are opened by file name: the app's linker namespace finds them inside the APK, extracted or not.
void load_cpu_backend() {
#ifdef GGML_BACKEND_DL
    static const char * const variants[] = {
        "libggml-cpu-android_armv9.0_1.so",
        "libggml-cpu-android_armv8.6_1.so",
        "libggml-cpu-android_armv8.2_2.so",
        "libggml-cpu-android_armv8.2_1.so",
        "libggml-cpu-android_armv8.0_1.so",
    };
    const char * best = nullptr;
    int best_score = 0;
    for (const char * name : variants) {
        void * handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) continue;
        auto * score = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
        const int s = score != nullptr ? score() : 0;
        dlclose(handle);
        if (s > best_score) {
            best_score = s;
            best = name;
        }
    }
    if (best != nullptr && ggml_backend_load(best) != nullptr) {
        LOGI("cpu backend: %s", best);
    } else {
        LOGI("no cpu backend could be loaded");
    }
#endif
}

// Default: one thread per fast core, at most 4 (past that, decoding is limited by memory bandwidth, not cores).
int resolve_threads(jint n_threads) {
    return n_threads > 0 ? n_threads : std::max(1, std::min(4, static_cast<int>(fast_cores().size())));
}

// A persistent thread pool pinned to the fast cores. Without one, ggml creates and destroys its worker threads on
// every token and the scheduler is free to put them on the slow cores (measured: 32 -> 50 tok/s with the pool).
// The pool starts paused so ggml pins the thread that computes (the session's own thread, which also does a share
// of the work) on the first decode, rather than the thread that happens to create the session. The functions come
// from the CPU backend's registry so this keeps working when the backend is a separately loaded library.
ggml_threadpool * make_pool(int threads) {
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (dev == nullptr) return nullptr;
    auto * pool_new = reinterpret_cast<decltype(ggml_threadpool_new) *>(
            ggml_backend_reg_get_proc_address(ggml_backend_dev_backend_reg(dev), "ggml_threadpool_new"));
    if (pool_new == nullptr) return nullptr;

    ggml_threadpool_params params = ggml_threadpool_params_default(threads);
    params.paused = true;
    const auto & cores = fast_cores();
    // Pin only when every thread gets a fast core of its own; asked for more threads than that, let the scheduler
    // spread them. If Android refuses the mask (the app in the background is limited to the slow cores), ggml
    // logs it and the threads simply run unpinned.
    if (threads <= static_cast<int>(cores.size()) &&
        cores.size() < static_cast<size_t>(std::thread::hardware_concurrency())) {
        for (int core : cores) {
            if (core < GGML_MAX_N_THREADS) params.cpumask[core] = true;
        }
        params.strict_cpu = true;
    }
    return pool_new(&params);
}

void free_pool(ggml_threadpool * pool) {
    if (pool == nullptr) return;
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    auto * pool_free = reinterpret_cast<decltype(ggml_threadpool_free) *>(
            ggml_backend_reg_get_proc_address(ggml_backend_dev_backend_reg(dev), "ggml_threadpool_free"));
    if (pool_free != nullptr) pool_free(pool);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    const int32_t needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           nullptr, 0, add_special, /* parse_special */ true);
    std::vector<llama_token> tokens(std::max(needed, 0));
    if (needed > 0) {
        llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                       tokens.data(), needed, add_special, true);
    }
    return tokens;
}

// Feeds `tokens` to the model in chunks of at most n_batch, asking for logits only on the very last one.
bool process_tokens(Session * s, const llama_token * tokens, int32_t count) {
    int32_t done = 0;
    while (done < count) {
        const int32_t chunk = std::min(s->n_batch, count - done);
        llama_batch_ext_clear(s->batch);
        const auto base = static_cast<llama_pos>(s->history.size());
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t idx = llama_batch_ext_add_token(s->batch, 0, tokens[done + i]);
            if (idx < 0) return false;
            const llama_pos pos = base + i;
            llama_batch_ext_set_pos(s->batch, idx, &pos);
        }
        const bool last_chunk = done + chunk == count;
        if (last_chunk) {
            llama_batch_ext_set_output_logits(s->batch, chunk - 1, true);
        }
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch) != 0) return false;
        s->history.insert(s->history.end(), tokens + done, tokens + done + chunk);
        done += chunk;
    }
    return true;
}

// Drops every token from position `keep` on, from both the KV cache and the history.
// Returns false when the memory cannot be cut there (recurrent models only support a full clear).
bool truncate_history(Session * s, size_t keep) {
    if (keep >= s->history.size()) return true;
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) return false;
    s->history.resize(keep);
    return true;
}

void clear_history(Session * s) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->history.clear();
}

void free_samplers(Session * s) {
    if (s->chain != nullptr) llama_sampler_free(s->chain);
    if (s->grammar != nullptr) llama_sampler_free(s->grammar);
    s->chain = nullptr;
    s->grammar = nullptr;
}

// The sampler chain of one generation. A repetition penalty (issue #2) would go right after top-k.
llama_sampler * build_chain(const SamplingConfig & c) {
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (c.temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (c.top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(c.top_k));
        if (c.top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(c.top_p, 1));
        if (c.min_p > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(c.min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(c.temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(c.seed));
    }
    return chain;
}

void fill_candidates(Session * s, const float * logits) {
    const int32_t n_vocab = llama_vocab_n_tokens(s->vocab);
    s->candidates.resize(n_vocab);
    for (int32_t id = 0; id < n_vocab; ++id) {
        s->candidates[id] = llama_token_data{ id, logits[id], 0.0f };
    }
}

// Picks the next token. With a grammar, the same trick as llama.cpp's own common sampler: sample without the
// grammar first and only check that one token against it, which is far cheaper than running the grammar over the
// whole vocabulary. If the grammar rejects it, sample again with the grammar applied first.
llama_token sample(Session * s) {
    const float * logits = llama_get_logits_ith(s->ctx, -1);
    if (logits == nullptr) return LLAMA_TOKEN_NULL;

    fill_candidates(s, logits);
    llama_token_data_array cur = { s->candidates.data(), s->candidates.size(), -1, false };
    llama_sampler_apply(s->chain, &cur);
    if (cur.selected < 0) return LLAMA_TOKEN_NULL;
    llama_token token = cur.data[cur.selected].id;

    if (s->grammar != nullptr) {
        llama_token_data single = { token, 1.0f, 0.0f };
        llama_token_data_array single_arr = { &single, 1, -1, false };
        llama_sampler_apply(s->grammar, &single_arr);
        if (std::isinf(single.logit) && single.logit < 0) {
            fill_candidates(s, logits);
            cur = { s->candidates.data(), s->candidates.size(), -1, false };
            llama_sampler_apply(s->grammar, &cur);
            llama_sampler_apply(s->chain, &cur);
            if (cur.selected < 0) return LLAMA_TOKEN_NULL;
            token = cur.data[cur.selected].id;
        }
        llama_sampler_accept(s->grammar, token);
    }
    llama_sampler_accept(s->chain, token);
    return token;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_backendInit(JNIEnv *, jclass) {
    std::call_once(g_backend_once, [] {
        load_cpu_backend();
        llama_backend_init();
    });
}

JNIEXPORT jlong JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_loadModel(JNIEnv * env, jclass, jstring jpath, jboolean use_mmap) {
    const std::string path = to_std_string(env, jpath);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0; // CPU only: predictable on every Android device.
    params.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        throw_java(env, "java/io/IOException", "Could not load a GGUF model from " + path);
        return 0;
    }
    LOGI("model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_freeModel(JNIEnv *, jclass, jlong handle) {
    llama_model_free(reinterpret_cast<llama_model *>(handle));
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_tokenCount(JNIEnv * env, jclass, jlong model_handle, jstring jtext,
                                                     jboolean add_special) {
    auto * model = reinterpret_cast<llama_model *>(model_handle);
    const std::string text = to_std_string(env, jtext);
    return -llama_tokenize(llama_model_get_vocab(model), text.c_str(), static_cast<int32_t>(text.size()),
                           nullptr, 0, add_special == JNI_TRUE, /* parse_special */ true);
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_embeddingSize(JNIEnv *, jclass, jlong model_handle) {
    return llama_model_n_embd_out(reinterpret_cast<llama_model *>(model_handle));
}

JNIEXPORT jstring JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_applyChatTemplate(
        JNIEnv * env, jclass, jlong model_handle, jobjectArray jroles, jobjectArray jcontents, jboolean add_assistant) {
    auto * model = reinterpret_cast<llama_model *>(model_handle);
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        throw_state(env, "This model has no chat template; use generate() with a raw prompt instead.");
        return nullptr;
    }

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> messages(n);
    for (jsize i = 0; i < n; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        roles[i] = to_std_string(env, role);
        contents[i] = to_std_string(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    for (jsize i = 0; i < n; ++i) {
        messages[i] = { roles[i].c_str(), contents[i].c_str() };
    }

    std::vector<char> buf(4096);
    int32_t len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), add_assistant == JNI_TRUE,
                                            buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), add_assistant == JNI_TRUE,
                                        buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) {
        throw_state(env, "llama.cpp does not support this model's chat template.");
        return nullptr;
    }
    return utf8_to_jstring(env, std::string(buf.data(), len));
}

// ---------------------------------------------------------------------------------------------------------------
// Sessions (text generation)
// ---------------------------------------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_newSession(
        JNIEnv * env, jclass, jlong model_handle,
        jint n_ctx, jint n_batch, jint n_threads,
        jfloat temperature, jint top_k, jfloat top_p, jfloat min_p, jint seed) {
    auto * model = reinterpret_cast<llama_model *>(model_handle);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = static_cast<uint32_t>(n_batch);
    cparams.n_ubatch = static_cast<uint32_t>(n_batch);
    const int threads = resolve_threads(n_threads);
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        throw_state(env, "Could not create a llama context (out of memory?).");
        return 0;
    }

    auto * s = new Session();
    s->ctx = ctx;
    s->pool = make_pool(threads);
    if (s->pool != nullptr) llama_attach_threadpool(ctx, s->pool, nullptr);
    s->batch = llama_batch_ext_init(ctx);
    s->vocab = llama_model_get_vocab(model);
    s->n_batch = n_batch;
    s->sampling.temperature = temperature;
    s->sampling.top_k = top_k;
    s->sampling.top_p = top_p;
    s->sampling.min_p = min_p;
    s->sampling.seed = static_cast<uint32_t>(seed);
    s->chain = build_chain(s->sampling);
    LOGI("session ready: n_ctx=%d n_batch=%d threads=%d fast_cores=%zu pool=%s", n_ctx, n_batch, threads,
         fast_cores().size(), s->pool != nullptr ? "pinned" : "none");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_freeSession(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    free_samplers(s);
    llama_batch_ext_free(s->batch);
    llama_free(s->ctx);
    free_pool(s->pool); // after the context, which uses it
    delete s;
}

// Rebuilds the sampler chain (fresh random state from the seed) for one generation and installs the grammar,
// if any. A temperature >= 0 overrides the session's for this generation only. Throws IllegalArgumentException
// when the grammar does not parse.
JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_beginGeneration(
        JNIEnv * env, jclass, jlong handle, jstring jgrammar, jstring jroot, jfloat temperature) {
    auto * s = reinterpret_cast<Session *>(handle);
    free_samplers(s);
    SamplingConfig config = s->sampling;
    if (temperature >= 0.0f) config.temperature = temperature;
    s->chain = build_chain(config);
    if (jgrammar != nullptr) {
        const std::string grammar = to_std_string(env, jgrammar);
        const std::string root = to_std_string(env, jroot);
        s->grammar = llama_sampler_init_grammar(s->vocab, grammar.c_str(), root.c_str());
        if (s->grammar == nullptr) {
            throw_java(env, "java/lang/IllegalArgumentException",
                       "llama.cpp could not parse the GBNF grammar (root rule \"" + root + "\").");
        }
    }
}

// Appends `text` to what the session holds. Returns the number of tokens processed.
JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_ingest(
        JNIEnv * env, jclass, jlong handle, jstring jtext, jboolean add_special) {
    auto * s = reinterpret_cast<Session *>(handle);
    const auto tokens = tokenize(s->vocab, to_std_string(env, jtext), add_special == JNI_TRUE);
    if (tokens.empty()) return 0;

    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    const auto used = static_cast<int32_t>(s->history.size());
    if (used + static_cast<int32_t>(tokens.size()) >= n_ctx) {
        throw_context_full(env, tokens.size(), n_ctx - used);
        return 0;
    }
    if (!process_tokens(s, tokens.data(), static_cast<int32_t>(tokens.size()))) {
        throw_state(env, "llama.cpp failed while processing the prompt.");
        return 0;
    }
    return static_cast<jint>(tokens.size());
}

// Makes the session hold exactly `text`, reusing the longest prefix already in the KV cache, so a chat that
// re-sends the whole conversation only pays for the new turn. Returns [prompt tokens, tokens reused].
JNIEXPORT jintArray JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_ingestReusingPrefix(
        JNIEnv * env, jclass, jlong handle, jstring jtext, jboolean add_special) {
    auto * s = reinterpret_cast<Session *>(handle);
    const auto tokens = tokenize(s->vocab, to_std_string(env, jtext), add_special == JNI_TRUE);

    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    if (static_cast<int32_t>(tokens.size()) >= n_ctx) {
        throw_context_full(env, tokens.size(), n_ctx);
        return nullptr;
    }

    size_t common = 0;
    while (common < tokens.size() && common < s->history.size() && tokens[common] == s->history[common]) {
        ++common;
    }
    // At least the last prompt token is processed again, so its logits are fresh for sampling.
    if (!tokens.empty() && common == tokens.size()) --common;
    if (!truncate_history(s, common)) {
        clear_history(s);
        common = 0;
    }
    const auto rest = static_cast<int32_t>(tokens.size() - common);
    if (rest > 0 && !process_tokens(s, tokens.data() + common, rest)) {
        throw_state(env, "llama.cpp failed while processing the prompt.");
        return nullptr;
    }

    jint out[2] = { static_cast<jint>(tokens.size()), static_cast<jint>(common) };
    jintArray result = env->NewIntArray(2);
    env->SetIntArrayRegion(result, 0, 2, out);
    return result;
}

// Samples one token, feeds it back to the model and returns its raw UTF-8 bytes.
// Returns null when the model emits an end-of-generation token or the context window is full.
JNIEXPORT jbyteArray JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_nextToken(JNIEnv * env, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (static_cast<int32_t>(s->history.size()) + 1 >= static_cast<int32_t>(llama_n_ctx(s->ctx))) return nullptr;

    const llama_token token = sample(s);
    if (token == LLAMA_TOKEN_NULL || llama_vocab_is_eog(s->vocab, token)) return nullptr;

    char piece[256];
    const int32_t n = llama_token_to_piece(s->vocab, token, piece, sizeof(piece), 0, /* special */ false);
    if (n < 0) {
        throw_state(env, "Could not convert a token to text.");
        return nullptr;
    }
    if (!process_tokens(s, &token, 1)) {
        throw_state(env, "llama.cpp failed while generating.");
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(n);
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(piece));
    return out;
}

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_reset(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    clear_history(s);
    llama_sampler_reset(s->chain);
    if (s->grammar != nullptr) llama_sampler_reset(s->grammar);
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_usedTokens(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(reinterpret_cast<Session *>(handle)->history.size());
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_contextSize(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<Session *>(handle)->ctx));
}

// Writes the KV cache and the token history to `path` (llama.cpp's session file format).
JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_saveState(JNIEnv * env, jclass, jlong handle, jstring jpath) {
    auto * s = reinterpret_cast<Session *>(handle);
    const std::string path = to_std_string(env, jpath);
    if (!llama_state_save_file(s->ctx, path.c_str(), s->history.data(), s->history.size())) {
        throw_java(env, "java/io/IOException", "Could not write the session state to " + path);
    }
}

// Restores a file written by saveState into this session. Returns the number of tokens restored.
JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_loadState(JNIEnv * env, jclass, jlong handle, jstring jpath) {
    auto * s = reinterpret_cast<Session *>(handle);
    const std::string path = to_std_string(env, jpath);
    std::vector<llama_token> tokens(llama_n_ctx(s->ctx));
    size_t count = 0;
    if (!llama_state_load_file(s->ctx, path.c_str(), tokens.data(), tokens.size(), &count)) {
        clear_history(s);
        throw_java(env, "java/io/IOException",
                   "Could not restore the session state from " + path +
                   " (written by another model or llama.cpp version, or for a bigger context window?)");
        return 0;
    }
    tokens.resize(count);
    s->history = std::move(tokens);

    // Recompute the logits of the last token so that generating right away (with an empty prompt) works.
    if (!s->history.empty()) {
        const llama_token last = s->history.back();
        if (truncate_history(s, s->history.size() - 1)) {
            if (!process_tokens(s, &last, 1)) {
                clear_history(s);
                throw_state(env, "llama.cpp failed while restoring the session state.");
                return 0;
            }
        }
    }
    llama_sampler_reset(s->chain);
    return static_cast<jint>(s->history.size());
}

// ---------------------------------------------------------------------------------------------------------------
// Embeddings
// ---------------------------------------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_newEmbedder(
        JNIEnv * env, jclass, jlong model_handle, jint n_ctx, jint n_threads) {
    auto * model = reinterpret_cast<llama_model *>(model_handle);
    if (llama_model_has_encoder(model) && llama_model_has_decoder(model)) {
        throw_state(env, "Embeddings are not supported for encoder-decoder models.");
        return 0;
    }

    const auto n_ctx_train = llama_model_n_ctx_train(model);
    const auto ctx_size = static_cast<uint32_t>(n_ctx_train > 0 ? std::min<int32_t>(n_ctx, n_ctx_train) : n_ctx);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctx_size;
    // The whole text goes through in one batch: pooling (mean, CLS, last) needs every token at once.
    cparams.n_batch = ctx_size;
    cparams.n_ubatch = ctx_size;
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED; // what the GGUF says
    const int threads = resolve_threads(n_threads);
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx != nullptr && llama_pooling_type(ctx) == LLAMA_POOLING_TYPE_NONE) {
        // A model without a pooling type (a plain chat model): average the token vectors.
        llama_free(ctx);
        cparams.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        ctx = llama_init_from_model(model, cparams);
    }
    if (ctx == nullptr) {
        throw_state(env, "Could not create an embedding context (out of memory?).");
        return 0;
    }
    if (llama_pooling_type(ctx) == LLAMA_POOLING_TYPE_RANK) {
        llama_free(ctx);
        throw_state(env, "This is a reranking model; it scores text pairs instead of producing embeddings.");
        return 0;
    }

    auto * e = new Embedder();
    e->ctx = ctx;
    e->batch = llama_batch_ext_init(ctx);
    e->vocab = llama_model_get_vocab(model);
    e->n_embd = llama_model_n_embd_out(model);
    LOGI("embedder ready: n_ctx=%u n_embd=%d pooling=%d", ctx_size, e->n_embd, llama_pooling_type(ctx));
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_freeEmbedder(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Embedder *>(handle);
    if (e == nullptr) return;
    llama_batch_ext_free(e->batch);
    llama_free(e->ctx);
    delete e;
}

// Returns the pooled (not normalized) embedding of `text`.
JNIEXPORT jfloatArray JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_embed(JNIEnv * env, jclass, jlong handle, jstring jtext) {
    auto * e = reinterpret_cast<Embedder *>(handle);
    const auto tokens = tokenize(e->vocab, to_std_string(env, jtext), /* add_special */ true);
    if (tokens.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Nothing to embed: the text has no tokens.");
        return nullptr;
    }
    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(e->ctx));
    if (static_cast<int32_t>(tokens.size()) > n_ctx) {
        throw_context_full(env, tokens.size(), n_ctx);
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_batch_ext_clear(e->batch);
    for (size_t i = 0; i < tokens.size(); ++i) {
        const int32_t idx = llama_batch_ext_add_token(e->batch, 0, tokens[i]);
        if (idx < 0) {
            throw_state(env, "The embedding batch is full.");
            return nullptr;
        }
        const auto pos = static_cast<llama_pos>(i);
        llama_batch_ext_set_pos(e->batch, idx, &pos);
        llama_batch_ext_set_output_embd(e->batch, idx, true);
    }
    if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch) < 0) {
        throw_state(env, "llama.cpp failed while computing the embedding.");
        return nullptr;
    }
    const float * embd = llama_get_embeddings_seq(e->ctx, 0);
    if (embd == nullptr) {
        throw_state(env, "This model did not return a sequence embedding.");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(e->n_embd);
    env->SetFloatArrayRegion(result, 0, e->n_embd, embd);
    return result;
}

} // extern "C"
