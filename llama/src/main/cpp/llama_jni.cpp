// JNI bridge between Kotlin and llama.cpp.
//
// Ownership model: Kotlin holds opaque `long` handles (a llama_model* or a Session*) and is responsible for
// calling the matching free function exactly once. A Session is never used from two threads at the same time:
// the Kotlin side confines each session to its own single-thread dispatcher.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama-kotlin"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_context *     ctx   = nullptr;
    llama_sampler *     smpl  = nullptr;
    llama_batch_ext *   batch = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t             n_pos   = 0;
    int32_t             n_batch = 0;
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

std::string to_std_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
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

// Feeds `tokens` to the model in chunks of at most n_batch, asking for logits only on the very last one.
bool process_tokens(Session * s, const llama_token * tokens, int32_t count) {
    int32_t done = 0;
    while (done < count) {
        const int32_t chunk = std::min(s->n_batch, count - done);
        llama_batch_ext_clear(s->batch);
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t idx = llama_batch_ext_add_token(s->batch, 0, tokens[done + i]);
            if (idx < 0) return false;
            const llama_pos pos = s->n_pos + i;
            llama_batch_ext_set_pos(s->batch, idx, &pos);
        }
        const bool last_chunk = done + chunk == count;
        if (last_chunk) {
            llama_batch_ext_set_output_logits(s->batch, chunk - 1, true);
        }
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch) != 0) return false;
        s->n_pos += chunk;
        done += chunk;
    }
    return true;
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

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_backendInit(JNIEnv *, jclass) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
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
    const int threads = n_threads > 0
            ? n_threads
            : std::max(1, std::min(4, static_cast<int>(std::thread::hardware_concurrency()) - 2));
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        throw_state(env, "Could not create a llama context (out of memory?).");
        return 0;
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    auto * s = new Session();
    s->ctx = ctx;
    s->smpl = smpl;
    s->batch = llama_batch_ext_init(ctx);
    s->vocab = llama_model_get_vocab(model);
    s->n_batch = n_batch;
    LOGI("session ready: n_ctx=%d n_batch=%d threads=%d", n_ctx, n_batch, threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_freeSession(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    llama_batch_ext_free(s->batch);
    llama_sampler_free(s->smpl);
    llama_free(s->ctx);
    delete s;
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_ingest(
        JNIEnv * env, jclass, jlong handle, jstring jtext, jboolean add_special) {
    auto * s = reinterpret_cast<Session *>(handle);
    const auto tokens = tokenize(s->vocab, to_std_string(env, jtext), add_special == JNI_TRUE);
    if (tokens.empty()) return 0;

    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    if (s->n_pos + static_cast<int32_t>(tokens.size()) >= n_ctx) {
        throw_java(env, "com/thalzeronlabs/llama/ContextFullException",
                   "Prompt needs " + std::to_string(tokens.size()) + " tokens but only " +
                   std::to_string(n_ctx - s->n_pos) + " are left in the context window.");
        return 0;
    }
    if (!process_tokens(s, tokens.data(), static_cast<int32_t>(tokens.size()))) {
        throw_state(env, "llama.cpp failed while processing the prompt.");
        return 0;
    }
    return static_cast<jint>(tokens.size());
}

// Samples one token, feeds it back to the model and returns its raw UTF-8 bytes.
// Returns null when the model emits an end-of-generation token or the context window is full.
JNIEXPORT jbyteArray JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_nextToken(JNIEnv * env, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s->n_pos + 1 >= static_cast<int32_t>(llama_n_ctx(s->ctx))) return nullptr;

    const llama_token token = llama_sampler_sample(s->smpl, s->ctx, -1);
    if (llama_vocab_is_eog(s->vocab, token)) return nullptr;

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
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_sampler_reset(s->smpl);
    s->n_pos = 0;
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_usedTokens(JNIEnv *, jclass, jlong handle) {
    return reinterpret_cast<Session *>(handle)->n_pos;
}

JNIEXPORT jint JNICALL
Java_com_thalzeronlabs_llama_NativeBridge_contextSize(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<Session *>(handle)->ctx));
}

} // extern "C"
