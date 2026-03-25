#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <vector>
#include <mutex>
#include <unistd.h>
#include <algorithm>

#include "llama.h"
#include "common.h"
#include "sampling.h"

#define TAG "NexusLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ===== Global State =====
static llama_model                  *g_model = nullptr;
static llama_context                *g_context = nullptr;
static llama_batch                   g_batch;
static common_sampler               *g_sampler = nullptr;
static std::mutex                    g_mutex;
static volatile bool                 g_abort = false;
static bool                          g_backend_initialized = false;

// Position tracking for context management
static llama_pos                     g_system_prompt_pos = 0;
static llama_pos                     g_current_pos = 0;

// Constants
constexpr int BATCH_SIZE = 512;
constexpr int OVERFLOW_HEADROOM = 4;
static int g_n_ctx = 4096;

// ===== Helpers =====

static bool is_valid_utf8(const char *str) {
    if (!str) return true;
    const auto *bytes = (const unsigned char *)str;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

static void reset_state(bool clear_kv = true) {
    g_system_prompt_pos = 0;
    g_current_pos = 0;
    if (clear_kv && g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

static void shift_context() {
    int n_discard = (g_current_pos - g_system_prompt_pos) / 2;
    LOGI("Shifting context: discarding %d tokens", n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0,
                        g_system_prompt_pos, g_system_prompt_pos + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0,
                         g_system_prompt_pos + n_discard, g_current_pos, -n_discard);
    g_current_pos -= n_discard;
}

static int decode_in_batches(const std::vector<llama_token> &tokens, llama_pos start_pos, bool logit_last = false) {
    // Safety: truncate if prompt exceeds context
    int n_tokens = (int)tokens.size();
    int max_tokens = g_n_ctx - OVERFLOW_HEADROOM;
    if (n_tokens > max_tokens) {
        LOGI("Prompt truncated from %d to %d tokens (context limit)", n_tokens, max_tokens);
        n_tokens = max_tokens;
    }

    for (int i = 0; i < n_tokens; i += BATCH_SIZE) {
        int cur_size = std::min(n_tokens - i, BATCH_SIZE);
        common_batch_clear(g_batch);

        for (int j = 0; j < cur_size; j++) {
            bool want_logit = logit_last && (i + j == n_tokens - 1);
            common_batch_add(g_batch, tokens[i + j], start_pos + i + j, {0}, want_logit);
        }

        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("llama_decode failed at batch offset %d", i);
            return 1;
        }
    }
    return 0;
}

// ===== JNI Functions =====

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_loadModel(
    JNIEnv *env, jobject,
    jstring modelPath, jint nThreads, jint contextLength
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    // Free previous
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_context) {
        llama_batch_free(g_batch);
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    reset_state(false);

    // Initialize backend (only once)
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("Backend initialized");
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    // Load model
    auto model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Create context
    g_n_ctx = contextLength;
    int threads = std::max(2, std::min((int)nThreads, (int)sysconf(_SC_NPROCESSORS_ONLN) - 2));

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = g_n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Initialize batch
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);

    // Initialize sampler
    common_params_sampling sparams;
    sparams.temp = 0.7f;
    sparams.top_p = 0.9f;
    g_sampler = common_sampler_init(g_model, sparams);

    LOGI("Model loaded: ctx=%d, threads=%d", g_n_ctx, threads);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_generate(
    JNIEnv *env, jobject,
    jstring prompt, jint maxTokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;

    if (!g_model || !g_context || !g_sampler) {
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Reset state for fresh generation
    reset_state(true);

    // The prompt is already formatted by the Kotlin layer (LlmEngine.buildPrompt).
    // IMPORTANT: add_special=false because Gemma 3 GGUF has add_bos_token=true in metadata,
    // so llama.cpp auto-adds BOS. Setting add_special=true would duplicate it → broken output.
    // parse_special=true interprets <start_of_turn> etc. as special tokens (required for Gemma 3).
    auto tokens = common_tokenize(g_context, promptCpp, false, true);
    LOGI("Prompt tokenized: %d tokens (prompt length: %d chars)", (int)tokens.size(), (int)promptCpp.size());

    if (tokens.empty()) {
        return env->NewStringUTF("[Error: Empty prompt after tokenization]");
    }

    // Decode prompt tokens
    if (decode_in_batches(tokens, 0, true)) {
        return env->NewStringUTF("[Error: Failed to process prompt]");
    }
    g_current_pos = (int)tokens.size();

    // Generate tokens
    std::string result;
    std::string cached_chars;
    const auto *vocab = llama_model_get_vocab(g_model);
    const auto gen_start = ggml_time_us();
    const int64_t GEN_TIMEOUT_US = 30 * 1000000LL; // 30 second timeout
    bool first_token_logged = false;

    for (int i = 0; i < maxTokens && !g_abort; i++) {
        // Timeout safety
        if (ggml_time_us() - gen_start > GEN_TIMEOUT_US) {
            LOGI("Generation timeout after %d tokens (%.1f seconds)",
                 i, (double)(ggml_time_us() - gen_start) / 1000000.0);
            break;
        }
        // Check context overflow
        if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGI("Context full during generation, shifting...");
            shift_context();
        }

        // Sample
        auto new_token = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, new_token, true);

        if (!first_token_logged) {
            LOGI("First token sampled: id=%d (%.1f ms after start)",
                 new_token, (double)(ggml_time_us() - gen_start) / 1000.0);
            first_token_logged = true;
        }

        // Check EOG
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGD("EOG token at position %d", i);
            break;
        }

        // Convert to text
        auto piece = common_token_to_piece(g_context, new_token);
        cached_chars += piece;

        // Only append valid UTF-8
        if (is_valid_utf8(cached_chars.c_str())) {
            result += cached_chars;
            cached_chars.clear();
        }

        // Decode the new token for next iteration
        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token, g_current_pos, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("llama_decode failed during generation at step %d", i);
            break;
        }
        g_current_pos++;
    }

    // Flush remaining cached chars
    if (!cached_chars.empty() && is_valid_utf8(cached_chars.c_str())) {
        result += cached_chars;
    }

    LOGI("Generated %d chars", (int)result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_nexus_companion_llm_LlamaJni_abort(JNIEnv *, jobject) {
    g_abort = true;
}

JNIEXPORT void JNICALL
Java_com_nexus_companion_llm_LlamaJni_unloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    reset_state(false);
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_context) {
        llama_batch_free(g_batch);
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_isModelLoaded(JNIEnv *, jobject) {
    return (g_model && g_context) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
