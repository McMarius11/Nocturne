#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"

#define TAG "NexusLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static llama_sampler *g_sampler = nullptr;
static std::mutex g_mutex;
static volatile bool g_abort = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_loadModel(
    JNIEnv *env, jobject /* this */,
    jstring modelPath, jint nThreads, jint contextLength
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    // Free previous model if loaded
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    // Model params
    auto model_params = llama_model_default_params();
    model_params.use_mmap = true;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Context params
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextLength;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Sampler
    auto sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(42));

    LOGI("Model loaded successfully, ctx=%d, threads=%d", contextLength, nThreads);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_nexus_companion_llm_LlamaJni_generate(
    JNIEnv *env, jobject /* this */,
    jstring prompt, jint maxTokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;

    if (!g_model || !g_ctx || !g_sampler) {
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Tokenize
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_prompt_tokens = promptCpp.size() + 16;
    std::vector<llama_token> tokens(n_prompt_tokens);
    n_prompt_tokens = llama_tokenize(vocab, promptCpp.c_str(), promptCpp.size(),
                                      tokens.data(), tokens.size(), true, true);
    if (n_prompt_tokens < 0) {
        tokens.resize(-n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(vocab, promptCpp.c_str(), promptCpp.size(),
                                          tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_prompt_tokens);

    // Clear KV cache
    llama_kv_cache_clear(g_ctx);

    // Process prompt in batch
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        return env->NewStringUTF("[Error: Failed to process prompt]");
    }

    // Generate tokens
    std::string result;
    const llama_token eos = llama_vocab_eos(vocab);
    char token_buf[256];

    for (int i = 0; i < maxTokens && !g_abort; i++) {
        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        int n = llama_token_to_piece(vocab, new_token, token_buf, sizeof(token_buf), 0, true);
        if (n > 0) {
            result.append(token_buf, n);
        }

        // Prepare next batch with the new token
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            break;
        }
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_nexus_companion_llm_LlamaJni_abort(
    JNIEnv *env, jobject /* this */
) {
    g_abort = true;
}

JNIEXPORT void JNICALL
Java_com_nexus_companion_llm_LlamaJni_unloadModel(
    JNIEnv *env, jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_llm_LlamaJni_isModelLoaded(
    JNIEnv *env, jobject /* this */
) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
