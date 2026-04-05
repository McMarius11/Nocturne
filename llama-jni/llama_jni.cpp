#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <vector>
#include <mutex>
#include <unistd.h>
#include <algorithm>
#include <cstdarg>

#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "ggml-backend.h"

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

// Last error message for detailed reporting
static std::string                   g_last_error;

// Constants
constexpr int BATCH_SIZE = 512;       // Logical batch size (max tokens per llama_decode call)
constexpr int UBATCH_SIZE = 128;      // Physical micro-batch — 128 optimal for Pixel 9 Pro big cores
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
    LOGI("Shifting context: discarding %d tokens (pos %d → %d)",
         n_discard, g_current_pos, g_current_pos - n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0,
                        g_system_prompt_pos, g_system_prompt_pos + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0,
                         g_system_prompt_pos + n_discard, g_current_pos, -n_discard);
    g_current_pos -= n_discard;
}

// Progress callback helpers (set before calling decode_in_batches)
static JNIEnv   *g_progress_env = nullptr;
static jobject    g_progress_cb  = nullptr;
static jmethodID  g_progress_mid = nullptr;

static void report_progress(const char *fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    LOGI("%s", buf);
    if (g_progress_env && g_progress_cb && g_progress_mid) {
        jstring jmsg = g_progress_env->NewStringUTF(buf);
        if (jmsg) {
            g_progress_env->CallVoidMethod(g_progress_cb, g_progress_mid, jmsg);
            g_progress_env->DeleteLocalRef(jmsg);
        }
    }
}

static int decode_in_batches(const std::vector<llama_token> &tokens, llama_pos start_pos, bool logit_last = false) {
    int n_tokens = (int)tokens.size();
    int max_tokens = g_n_ctx - OVERFLOW_HEADROOM;
    if (n_tokens > max_tokens) {
        report_progress("Prompt truncated from %d to %d tokens (context limit)", n_tokens, max_tokens);
        n_tokens = max_tokens;
    }

    int n_batches = (n_tokens + BATCH_SIZE - 1) / BATCH_SIZE;
    report_progress("Decoding %d tokens in %d batch(es), threads=%d", n_tokens, n_batches,
                    llama_n_threads(g_context));

    for (int i = 0; i < n_tokens; i += BATCH_SIZE) {
        if (g_abort) return 2; // abort code
        int cur_size = std::min(n_tokens - i, BATCH_SIZE);
        int batch_idx = i / BATCH_SIZE + 1;
        common_batch_clear(g_batch);

        for (int j = 0; j < cur_size; j++) {
            bool want_logit = logit_last && (i + j == n_tokens - 1);
            common_batch_add(g_batch, tokens[i + j], start_pos + i + j, {0}, want_logit);
        }

        report_progress("Batch %d/%d: %d tokens, calling llama_decode...", batch_idx, n_batches, cur_size);
        auto batch_start = ggml_time_us();
        int rc = llama_decode(g_context, g_batch);
        auto batch_ms = (ggml_time_us() - batch_start) / 1000;

        if (rc != 0) {
            report_progress("llama_decode FAILED at batch %d (rc=%d, %lld ms)", batch_idx, rc, (long long)batch_ms);
            return 1;
        }
        report_progress("Batch %d/%d decoded in %lld ms", batch_idx, n_batches, (long long)batch_ms);
    }
    return 0;
}

// ===== JNI Functions =====

extern "C" {

/**
 * Load all CPU backend variants from the app's native library directory.
 * Only needed with GGML_BACKEND_DL=ON. With static linking, this is a no-op
 * but kept for API compatibility.
 */
JNIEXPORT void JNICALL
Java_com_nexus_companion_llm_LlamaJni_loadBackends(
    JNIEnv *env, jobject,
    jstring nativeLibDir
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    // With static linking (BUILD_SHARED_LIBS=OFF), backends are compiled in.
    // Just log for debugging.
    const char *path = env->GetStringUTFChars(nativeLibDir, nullptr);
    LOGI("loadBackends called (static build, path=%s)", path ? path : "null");
    if (path) env->ReleaseStringUTFChars(nativeLibDir, path);
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_loadModel(
    JNIEnv *env, jobject,
    jstring modelPath, jint nThreads, jint contextLength
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();

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
        LOGI("Backend initialized (static build)");
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        g_last_error = "Failed to get model path string (OOM?)";
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }
    LOGI("Loading model: %s (ctx=%d, threads=%d)", path, contextLength, nThreads);

    // Check file accessible
    if (access(path, R_OK) != 0) {
        g_last_error = "Model file not readable: ";
        g_last_error += path;
        LOGE("%s", g_last_error.c_str());
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }

    // Load model — offload all layers to GPU if Vulkan is available
    auto model_params = llama_model_default_params();
    // Check if a GPU device is available (Vulkan backend)
    bool has_gpu = false;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            has_gpu = true;
            LOGI("GPU device found: %s (%s)", ggml_backend_dev_name(dev),
                 ggml_backend_dev_description(dev));
            break;
        }
    }
    if (has_gpu) {
        model_params.n_gpu_layers = 99; // Offload all layers to GPU
        LOGI("GPU offload: all layers → Vulkan");
    } else {
        LOGI("No GPU device — using CPU only");
    }
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        g_last_error = "llama_model_load_from_file failed (OOM or incompatible GGUF)";
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    // Create context
    g_n_ctx = contextLength;
    int n_cpus = (int)sysconf(_SC_NPROCESSORS_ONLN);
    // Pixel 9 Pro Tensor G4: 1xX4 + 3xA720 + 4xA520 = 8 cores.
    // Generation (sequential): 4 threads on big cores for best single-token latency.
    // Batch/prompt decode (parallel): 6 threads to saturate big+mid cores for throughput.
    int gen_threads = std::max(1, std::min((int)nThreads, n_cpus - 2));
    int batch_threads = std::max(gen_threads, std::min(n_cpus - 1, 6));
    LOGI("CPU cores: %d, gen_threads=%d, batch_threads=%d", n_cpus, gen_threads, batch_threads);

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = g_n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = UBATCH_SIZE;         // 128: good throughput on Pixel 9 Pro big cores
    ctx_params.n_threads = gen_threads;
    ctx_params.n_threads_batch = batch_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_params.type_k = GGML_TYPE_Q8_0;        // KV cache quantization: 50% less memory bandwidth
    ctx_params.type_v = GGML_TYPE_Q8_0;        // Negligible quality loss, major speed gain

    LOGI("Context params: ctx=%d batch=%d ubatch=%d gen_threads=%d batch_threads=%d flash_attn=on kv=q8_0",
         g_n_ctx, BATCH_SIZE, UBATCH_SIZE, gen_threads, batch_threads);

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        // Flash attention or Q8_0 KV might not be supported — retry with defaults
        LOGI("Context creation failed, retrying with default KV type and no flash attention...");
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        g_context = llama_init_from_model(g_model, ctx_params);
    }
    if (!g_context) {
        g_last_error = "llama_init_from_model failed (not enough RAM for context)";
        LOGE("%s", g_last_error.c_str());
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Initialize batch
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);

    // Initialize sampler with good defaults
    common_params_sampling sparams;
    sparams.temp = 0.7f;
    sparams.top_p = 0.9f;
    sparams.top_k = 40;
    sparams.penalty_repeat = 1.1f;
    g_sampler = common_sampler_init(g_model, sparams);

    if (!g_sampler) {
        g_last_error = "common_sampler_init failed";
        LOGE("%s", g_last_error.c_str());
        llama_batch_free(g_batch);
        llama_free(g_context);
        g_context = nullptr;
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded OK: ctx=%d, gen_threads=%d, batch_threads=%d, vocab=%d",
         g_n_ctx, gen_threads, batch_threads, llama_vocab_n_tokens(llama_model_get_vocab(g_model)));
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_generate(
    JNIEnv *env, jobject,
    jstring prompt, jint maxTokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;
    g_last_error.clear();

    if (!g_model || !g_context || !g_sampler) {
        g_last_error = "Model not loaded";
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (!promptStr) {
        g_last_error = "Failed to get prompt string (OOM?)";
        return env->NewStringUTF("[Error: OOM getting prompt]");
    }
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Reset state for fresh generation
    reset_state(true);
    common_sampler_reset(g_sampler);

    // Tokenize the prompt.
    // add_special=false: most GGUFs have add_bos_token=true in metadata,
    //   llama.cpp auto-adds BOS. Setting add_special=true duplicates it → broken output.
    // parse_special=true: interprets <start_of_turn>, <|im_start|> etc. as special tokens.
    auto tokens = common_tokenize(g_context, promptCpp, false, true);
    LOGI("Tokenized: %d tokens from %d chars", (int)tokens.size(), (int)promptCpp.size());

    if (tokens.empty()) {
        g_last_error = "Empty prompt after tokenization";
        LOGE("Empty prompt after tokenization (input was %d chars)", (int)promptCpp.size());
        return env->NewStringUTF("[Error: Empty prompt after tokenization]");
    }

    if ((int)tokens.size() >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGI("WARNING: Prompt (%d tokens) fills context (%d). Truncating.", (int)tokens.size(), g_n_ctx);
    }

    // Log first few tokens for debugging
    {
        std::string tok_debug;
        int n_show = std::min((int)tokens.size(), 8);
        for (int i = 0; i < n_show; i++) {
            tok_debug += std::to_string(tokens[i]);
            if (i < n_show - 1) tok_debug += ",";
        }
        if ((int)tokens.size() > n_show) tok_debug += "...";
        LOGI("First tokens: [%s]", tok_debug.c_str());
    }

    // Decode prompt tokens
    const auto decode_start = ggml_time_us();
    int decode_result = decode_in_batches(tokens, 0, true);
    const auto decode_ms = (ggml_time_us() - decode_start) / 1000;

    if (decode_result == 2) {
        // Aborted
        return env->NewStringUTF("");
    }
    if (decode_result != 0) {
        g_last_error = "llama_decode failed during prompt processing";
        return env->NewStringUTF("[Error: Failed to process prompt]");
    }

    g_current_pos = (int)tokens.size();
    LOGI("Prompt decoded in %lld ms (%d tokens, %.1f t/s)",
         (long long)decode_ms, (int)tokens.size(),
         decode_ms > 0 ? (tokens.size() * 1000.0 / decode_ms) : 0.0);

    // Generate tokens
    std::string result;
    std::string cached_chars;
    const auto *vocab = llama_model_get_vocab(g_model);
    const auto gen_start = ggml_time_us();
    const int64_t GEN_TIMEOUT_US = 60 * 1000000LL; // 60 second timeout (was 30s)
    int tokens_generated = 0;

    for (int i = 0; i < maxTokens && !g_abort; i++) {
        // Timeout safety
        if (ggml_time_us() - gen_start > GEN_TIMEOUT_US) {
            LOGI("Generation timeout after %d tokens (%.1f seconds)",
                 i, (double)(ggml_time_us() - gen_start) / 1000000.0);
            g_last_error = "Generation timeout (60s)";
            break;
        }
        // Check context overflow
        if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGI("Context full at pos %d, shifting...", g_current_pos);
            shift_context();
        }

        // Sample
        auto new_token = common_sampler_sample(g_sampler, g_context, -1);

        // Validate token before accepting
        int n_vocab = llama_vocab_n_tokens(vocab);
        if (new_token < 0 || new_token >= n_vocab) {
            LOGE("Invalid token %d (vocab size %d) at step %d", new_token, n_vocab, i);
            g_last_error = "Sampler returned invalid token";
            break;
        }

        common_sampler_accept(g_sampler, new_token, true);

        if (tokens_generated == 0) {
            auto first_tok_ms = (ggml_time_us() - gen_start) / 1000;
            LOGI("First token: id=%d latency=%lld ms", new_token, (long long)first_tok_ms);
        }

        // Check EOG
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGD("EOG at token %d", i);
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

        tokens_generated++;

        // Decode the new token for next iteration
        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token, g_current_pos, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("llama_decode failed during generation at token %d", i);
            g_last_error = "Decode failed during generation";
            break;
        }
        g_current_pos++;
    }

    // Flush remaining cached chars
    if (!cached_chars.empty() && is_valid_utf8(cached_chars.c_str())) {
        result += cached_chars;
    }

    auto gen_ms = (ggml_time_us() - gen_start) / 1000;
    double tok_per_sec = gen_ms > 0 ? (tokens_generated * 1000.0 / gen_ms) : 0.0;
    LOGI("Generated %d tokens (%d chars) in %lld ms (%.1f t/s)%s",
         tokens_generated, (int)result.size(), (long long)gen_ms, tok_per_sec,
         g_abort ? " [ABORTED]" : "");

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_generateStreaming(
    JNIEnv *env, jobject obj,
    jstring prompt, jint maxTokens, jobject callback
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;
    g_last_error.clear();

    if (!g_model || !g_context || !g_sampler) {
        g_last_error = "Model not loaded";
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    // Get callback methods (delete local ref to class immediately — we only need the method IDs)
    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) {
        g_last_error = "GetObjectClass returned null";
        return env->NewStringUTF("[Error: Invalid callback object]");
    }
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(callbackClass);
    if (!onTokenMethod || !onProgressMethod) {
        env->ExceptionClear(); // Clear pending NoSuchMethodException before further JNI calls
        LOGE("Callback methods not found (onToken=%p, onProgress=%p)", onTokenMethod, onProgressMethod);
        return env->NewStringUTF("[Error: Invalid callback]");
    }

    // Set up progress reporting so decode_in_batches can call back to Kotlin
    g_progress_env = env;
    g_progress_cb  = callback;
    g_progress_mid = onProgressMethod;

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (!promptStr) {
        g_last_error = "Failed to get prompt string (OOM?)";
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("[Error: OOM getting prompt]");
    }
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Reset state
    reset_state(true);
    common_sampler_reset(g_sampler);

    report_progress("Tokenizing %d chars (parse_special=true)...", (int)promptCpp.size());
    auto tokens = common_tokenize(g_context, promptCpp, false, true);
    report_progress("Tokenized: %d tokens from %d chars", (int)tokens.size(), (int)promptCpp.size());

    if (tokens.empty()) {
        g_last_error = "Empty prompt after tokenization";
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("[Error: Empty prompt after tokenization]");
    }

    // Log first tokens
    {
        std::string tok_debug;
        int n_show = std::min((int)tokens.size(), 8);
        for (int i = 0; i < n_show; i++) {
            tok_debug += std::to_string(tokens[i]);
            if (i < n_show - 1) tok_debug += ",";
        }
        report_progress("First tokens: [%s]", tok_debug.c_str());
    }

    // Decode prompt
    report_progress("Starting prompt decode (n_threads=%d, n_threads_batch=%d)...",
                    llama_n_threads(g_context), llama_n_threads_batch(g_context));
    const auto decode_start = ggml_time_us();
    int decode_result = decode_in_batches(tokens, 0, true);
    auto decode_ms = (ggml_time_us() - decode_start) / 1000;

    if (decode_result == 2) {
        report_progress("Prompt decode aborted");
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("");
    }
    if (decode_result != 0) {
        g_last_error = "Decode failed during prompt processing";
        report_progress("Prompt decode FAILED (rc=%d)", decode_result);
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("[Error: Failed to process prompt]");
    }

    g_current_pos = (int)tokens.size();
    report_progress("Prompt decoded in %lld ms (%.1f t/s)",
         (long long)decode_ms,
         decode_ms > 0 ? (tokens.size() * 1000.0 / decode_ms) : 0.0);

    // Generate with streaming
    std::string result;
    std::string cached_chars;
    const auto *vocab = llama_model_get_vocab(g_model);
    const auto gen_start = ggml_time_us();
    const int64_t GEN_TIMEOUT_US = 60 * 1000000LL;
    int tokens_generated = 0;

    for (int i = 0; i < maxTokens && !g_abort; i++) {
        if (ggml_time_us() - gen_start > GEN_TIMEOUT_US) {
            LOGI("Streaming timeout after %d tokens", i);
            break;
        }
        if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM) {
            shift_context();
        }

        auto new_token = common_sampler_sample(g_sampler, g_context, -1);

        // Validate token before accepting
        int n_vocab = llama_vocab_n_tokens(vocab);
        if (new_token < 0 || new_token >= n_vocab) {
            LOGE("Invalid token %d (vocab size %d) at step %d", new_token, n_vocab, i);
            g_last_error = "Sampler returned invalid token";
            break;
        }

        common_sampler_accept(g_sampler, new_token, true);

        if (tokens_generated == 0) {
            auto first_tok_ms = (ggml_time_us() - gen_start) / 1000;
            LOGI("First token: id=%d latency=%lld ms", new_token, (long long)first_tok_ms);
        }

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGD("EOG at token %d", i);
            break;
        }

        auto piece = common_token_to_piece(g_context, new_token);
        cached_chars += piece;

        if (is_valid_utf8(cached_chars.c_str())) {
            result += cached_chars;
            // Stream each valid UTF-8 chunk to Kotlin
            jstring jPiece = env->NewStringUTF(cached_chars.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jPiece);
            env->DeleteLocalRef(jPiece);
            cached_chars.clear();
        }

        tokens_generated++;

        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token, g_current_pos, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("Decode failed at generation token %d", i);
            break;
        }
        g_current_pos++;
    }

    // Flush remaining cached chars
    if (!cached_chars.empty()) {
        if (is_valid_utf8(cached_chars.c_str())) {
            result += cached_chars;
        } else {
            // Incomplete UTF-8 sequence at end — replace with U+FFFD
            result += "\xEF\xBF\xBD";
        }
        jstring jPiece = env->NewStringUTF(
            is_valid_utf8(cached_chars.c_str()) ? cached_chars.c_str() : "\xEF\xBF\xBD");
        env->CallVoidMethod(callback, onTokenMethod, jPiece);
        env->DeleteLocalRef(jPiece);
    }

    auto gen_ms = (ggml_time_us() - gen_start) / 1000;
    double tok_per_sec = gen_ms > 0 ? (tokens_generated * 1000.0 / gen_ms) : 0.0;
    report_progress("Streamed %d tokens (%d chars) in %lld ms (%.1f t/s)%s",
         tokens_generated, (int)result.size(), (long long)gen_ms, tok_per_sec,
         g_abort ? " [ABORTED]" : "");

    // Clean up progress callback
    g_progress_env = nullptr;
    g_progress_cb  = nullptr;
    g_progress_mid = nullptr;

    return env->NewStringUTF(result.c_str());
}

/**
 * Continue generation without resetting KV cache.
 * Appends newText tokens to the existing context and generates.
 * Used for multi-turn conversations where system prompt + history is already cached.
 */
JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_continueStreaming(
    JNIEnv *env, jobject obj,
    jstring newText, jint maxTokens, jobject callback
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;
    g_last_error.clear();

    if (!g_model || !g_context || !g_sampler) {
        g_last_error = "Model not loaded";
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    // Get callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) {
        return env->NewStringUTF("[Error: Invalid callback object]");
    }
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(callbackClass);
    if (!onTokenMethod || !onProgressMethod) {
        env->ExceptionClear();
        return env->NewStringUTF("[Error: Invalid callback]");
    }

    g_progress_env = env;
    g_progress_cb  = callback;
    g_progress_mid = onProgressMethod;

    const char *textStr = env->GetStringUTFChars(newText, nullptr);
    if (!textStr) {
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("[Error: OOM getting text]");
    }
    std::string textCpp(textStr);
    env->ReleaseStringUTFChars(newText, textStr);

    // Do NOT reset KV cache — keep existing context
    common_sampler_reset(g_sampler);

    report_progress("Continue: tokenizing %d chars, current_pos=%d", (int)textCpp.size(), g_current_pos);
    auto tokens = common_tokenize(g_context, textCpp, false, true);
    report_progress("Continue: %d new tokens, appending at pos=%d", (int)tokens.size(), g_current_pos);

    if (tokens.empty()) {
        g_last_error = "Empty text after tokenization";
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("[Error: Empty continuation text]");
    }

    // Check if we have room in the context
    if (g_current_pos + (int)tokens.size() >= g_n_ctx - OVERFLOW_HEADROOM) {
        report_progress("Context would overflow (%d + %d >= %d), shifting...",
                        g_current_pos, (int)tokens.size(), g_n_ctx);
        shift_context();
    }

    // Decode new tokens from current position
    const auto decode_start = ggml_time_us();
    int decode_result = decode_in_batches(tokens, g_current_pos, true);
    auto decode_ms = (ggml_time_us() - decode_start) / 1000;

    if (decode_result == 2) {
        report_progress("Continue decode aborted");
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("");
    }
    if (decode_result != 0) {
        g_last_error = "Decode failed during continuation";
        report_progress("Continue decode FAILED (rc=%d)", decode_result);
        g_progress_env = nullptr; g_progress_cb = nullptr; g_progress_mid = nullptr;
        return env->NewStringUTF("[Error: Failed to decode continuation]");
    }

    g_current_pos += (int)tokens.size();
    report_progress("Continue decoded in %lld ms, pos=%d", (long long)decode_ms, g_current_pos);

    // Generate tokens (same logic as generateStreaming)
    std::string result;
    std::string cached_chars;
    const auto *vocab = llama_model_get_vocab(g_model);
    const auto gen_start = ggml_time_us();
    const int64_t GEN_TIMEOUT_US = 60 * 1000000LL;
    int tokens_generated = 0;

    for (int i = 0; i < maxTokens && !g_abort; i++) {
        if (ggml_time_us() - gen_start > GEN_TIMEOUT_US) {
            LOGI("Continue timeout after %d tokens", i);
            break;
        }
        if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM) {
            shift_context();
        }

        auto new_token = common_sampler_sample(g_sampler, g_context, -1);

        int n_vocab = llama_vocab_n_tokens(vocab);
        if (new_token < 0 || new_token >= n_vocab) {
            LOGE("Invalid token %d at step %d", new_token, i);
            break;
        }

        common_sampler_accept(g_sampler, new_token, true);

        if (tokens_generated == 0) {
            auto first_tok_ms = (ggml_time_us() - gen_start) / 1000;
            LOGI("Continue first token: id=%d latency=%lld ms", new_token, (long long)first_tok_ms);
        }

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGD("EOG at token %d", i);
            break;
        }

        auto piece = common_token_to_piece(g_context, new_token);
        cached_chars += piece;

        if (is_valid_utf8(cached_chars.c_str())) {
            result += cached_chars;
            jstring jPiece = env->NewStringUTF(cached_chars.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jPiece);
            env->DeleteLocalRef(jPiece);
            cached_chars.clear();
        }

        tokens_generated++;

        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token, g_current_pos, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("Decode failed at continue token %d", i);
            break;
        }
        g_current_pos++;
    }

    if (!cached_chars.empty()) {
        if (is_valid_utf8(cached_chars.c_str())) {
            result += cached_chars;
        } else {
            result += "\xEF\xBF\xBD";
        }
    }

    auto gen_ms = (ggml_time_us() - gen_start) / 1000;
    double tok_per_sec = gen_ms > 0 ? (tokens_generated * 1000.0 / gen_ms) : 0.0;
    report_progress("Continue: %d tokens in %lld ms (%.1f t/s), pos=%d",
         tokens_generated, (long long)gen_ms, tok_per_sec, g_current_pos);

    g_progress_env = nullptr;
    g_progress_cb  = nullptr;
    g_progress_mid = nullptr;

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jint JNICALL
Java_com_nexus_companion_llm_LlamaJni_getCurrentPosition(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_current_pos;
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

JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_getLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_getModelInfo(JNIEnv *env, jobject) {
    if (!g_model || !g_context) {
        return env->NewStringUTF("No model loaded");
    }
    const auto *vocab = llama_model_get_vocab(g_model);
    std::string info;
    info += "ctx=" + std::to_string(g_n_ctx);
    info += " vocab=" + std::to_string(llama_vocab_n_tokens(vocab));
    info += " pos=" + std::to_string(g_current_pos);
    return env->NewStringUTF(info.c_str());
}

/**
 * Smoke-test: tokenize "hello", decode 1 batch, sample 1 token.
 * Returns a JSON-like string with timing info, or an error message.
 * This tells us if llama_decode works AT ALL on this device.
 */
JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_benchmarkDecode(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();

    if (!g_model || !g_context || !g_sampler) {
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    // Reset state
    reset_state(true);
    common_sampler_reset(g_sampler);

    // Tokenize a tiny prompt
    auto tokens = common_tokenize(g_context, "hello", false, false);
    if (tokens.empty()) {
        return env->NewStringUTF("ERROR: Failed to tokenize 'hello'");
    }

    int n_threads = llama_n_threads(g_context);
    LOGI("benchmarkDecode: %d tokens, %d threads", (int)tokens.size(), n_threads);

    // Decode the tiny prompt
    common_batch_clear(g_batch);
    for (int i = 0; i < (int)tokens.size(); i++) {
        common_batch_add(g_batch, tokens[i], i, {0}, i == (int)tokens.size() - 1);
    }

    auto t0 = ggml_time_us();
    int rc = llama_decode(g_context, g_batch);
    auto decode_us = ggml_time_us() - t0;

    if (rc != 0) {
        char buf[256];
        snprintf(buf, sizeof(buf), "ERROR: llama_decode returned %d after %lld ms",
                 rc, (long long)(decode_us / 1000));
        return env->NewStringUTF(buf);
    }

    // Sample one token
    auto t1 = ggml_time_us();
    auto tok = common_sampler_sample(g_sampler, g_context, -1);
    auto sample_us = ggml_time_us() - t1;

    auto piece = common_token_to_piece(g_context, tok);

    char result[512];
    snprintf(result, sizeof(result),
             "OK: decode=%lldms sample=%lldms threads=%d tok=%d piece=\"%.20s\"",
             (long long)(decode_us / 1000), (long long)(sample_us / 1000),
             n_threads, tok, piece.c_str());

    LOGI("benchmarkDecode: %s", result);
    reset_state(true);
    common_sampler_reset(g_sampler);
    return env->NewStringUTF(result);
}

/**
 * Returns info about registered GGML backends and devices.
 */
JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_getBackendInfo(JNIEnv *env, jobject) {
    std::string info;

    size_t n_reg = ggml_backend_reg_count();
    info += "Backends (" + std::to_string(n_reg) + "):";
    for (size_t i = 0; i < n_reg; i++) {
        auto reg = ggml_backend_reg_get(i);
        info += " [" + std::string(ggml_backend_reg_name(reg)) + "]";
    }

    size_t n_dev = ggml_backend_dev_count();
    info += " | Devices (" + std::to_string(n_dev) + "):";
    for (size_t i = 0; i < n_dev; i++) {
        auto dev = ggml_backend_dev_get(i);
        info += " [" + std::string(ggml_backend_dev_name(dev))
              + ": " + std::string(ggml_backend_dev_description(dev)) + "]";
    }

    LOGI("Backend info: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

/**
 * Pre-decode the system prompt so it's cached in KV.
 * Subsequent generateStreaming/continueStreaming calls can skip re-decoding it.
 * Call this once after loadModel to warm up the first-message path.
 */
JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_warmUpSystemPrompt(
    JNIEnv *env, jobject, jstring systemPrompt
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;
    g_last_error.clear();

    if (!g_model || !g_context || !g_sampler) {
        g_last_error = "Model not loaded";
        return JNI_FALSE;
    }

    const char *str = env->GetStringUTFChars(systemPrompt, nullptr);
    if (!str) {
        g_last_error = "OOM getting system prompt";
        return JNI_FALSE;
    }
    std::string promptCpp(str);
    env->ReleaseStringUTFChars(systemPrompt, str);

    // Reset KV cache — start fresh
    reset_state(true);
    common_sampler_reset(g_sampler);

    auto tokens = common_tokenize(g_context, promptCpp, false, true);
    if (tokens.empty()) {
        g_last_error = "Empty system prompt after tokenization";
        return JNI_FALSE;
    }

    LOGI("Warm-up: decoding %d system prompt tokens...", (int)tokens.size());
    auto t0 = ggml_time_us();
    int rc = decode_in_batches(tokens, 0, false); // no logits needed for prefill
    auto ms = (ggml_time_us() - t0) / 1000;

    if (rc != 0) {
        LOGE("Warm-up decode failed (rc=%d)", rc);
        g_last_error = "System prompt decode failed";
        reset_state(true);
        return JNI_FALSE;
    }

    g_current_pos = (int)tokens.size();
    g_system_prompt_pos = g_current_pos;
    LOGI("Warm-up done: %d tokens in %lld ms (pos=%d)", (int)tokens.size(), (long long)ms, g_current_pos);
    return JNI_TRUE;
}

/**
 * Recreate the context with a different thread count.
 * Used for thread-count fallback (e.g. try 1 thread if 2 hangs).
 */
JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_setThreadCount(JNIEnv *env, jobject, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_context) {
        LOGE("setThreadCount: no model loaded");
        return JNI_FALSE;
    }

    int threads = std::max(1, std::min((int)nThreads, (int)sysconf(_SC_NPROCESSORS_ONLN)));
    LOGI("Recreating context with %d threads (was %d)", threads, llama_n_threads(g_context));

    // Save current context size
    int ctx_size = g_n_ctx;

    // Free old context (keep model and sampler)
    reset_state(false);
    llama_batch_free(g_batch);
    llama_free(g_context);
    g_context = nullptr;

    // Recreate with new thread count (same optimizations as loadModel)
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = ctx_size;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = UBATCH_SIZE;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        // Fallback without optimizations
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        g_context = llama_init_from_model(g_model, ctx_params);
    }
    if (!g_context) {
        LOGE("Failed to recreate context with %d threads", threads);
        return JNI_FALSE;
    }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    LOGI("Context recreated: threads=%d", threads);
    return JNI_TRUE;
}

} // extern "C"
