#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cmath>
#include "llama.h"

#define TAG "NexusTts"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_tts_model = nullptr;
static llama_context *g_tts_ctx = nullptr;
static llama_model *g_vocoder_model = nullptr;
static llama_context *g_vocoder_ctx = nullptr;
static std::mutex g_tts_mutex;
static volatile bool g_tts_abort = false;

// Audio parameters matching OuteTTS
static const int SAMPLE_RATE = 24000;
static const int N_CODEBOOKS = 3; // OuteTTS 0.3 uses 3 codebook levels

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_tts_TtsJni_loadTtsModel(
    JNIEnv *env, jobject /* this */,
    jstring ttsModelPath, jstring vocoderPath, jint nThreads
) {
    std::lock_guard<std::mutex> lock(g_tts_mutex);

    // Free previous models
    if (g_vocoder_ctx) { llama_free(g_vocoder_ctx); g_vocoder_ctx = nullptr; }
    if (g_vocoder_model) { llama_model_free(g_vocoder_model); g_vocoder_model = nullptr; }
    if (g_tts_ctx) { llama_free(g_tts_ctx); g_tts_ctx = nullptr; }
    if (g_tts_model) { llama_model_free(g_tts_model); g_tts_model = nullptr; }

    // Load TTS model (OuteTTS)
    const char *ttsPath = env->GetStringUTFChars(ttsModelPath, nullptr);
    LOGI("Loading TTS model: %s", ttsPath);

    auto model_params = llama_model_default_params();
    model_params.use_mmap = true;

    g_tts_model = llama_model_load_from_file(ttsPath, model_params);
    env->ReleaseStringUTFChars(ttsModelPath, ttsPath);

    if (!g_tts_model) {
        LOGE("Failed to load TTS model");
        return JNI_FALSE;
    }

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_tts_ctx = llama_init_from_model(g_tts_model, ctx_params);
    if (!g_tts_ctx) {
        LOGE("Failed to create TTS context");
        llama_model_free(g_tts_model);
        g_tts_model = nullptr;
        return JNI_FALSE;
    }

    // Load vocoder model (WavTokenizer)
    const char *vocPath = env->GetStringUTFChars(vocoderPath, nullptr);
    LOGI("Loading vocoder: %s", vocPath);

    g_vocoder_model = llama_model_load_from_file(vocPath, model_params);
    env->ReleaseStringUTFChars(vocoderPath, vocPath);

    if (!g_vocoder_model) {
        LOGE("Failed to load vocoder model");
        llama_free(g_tts_ctx); g_tts_ctx = nullptr;
        llama_model_free(g_tts_model); g_tts_model = nullptr;
        return JNI_FALSE;
    }

    auto voc_ctx_params = llama_context_default_params();
    voc_ctx_params.n_ctx = 8192;
    voc_ctx_params.n_threads = nThreads;
    voc_ctx_params.n_threads_batch = nThreads;

    g_vocoder_ctx = llama_init_from_model(g_vocoder_model, voc_ctx_params);
    if (!g_vocoder_ctx) {
        LOGE("Failed to create vocoder context");
        llama_model_free(g_vocoder_model); g_vocoder_model = nullptr;
        llama_free(g_tts_ctx); g_tts_ctx = nullptr;
        llama_model_free(g_tts_model); g_tts_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("TTS models loaded successfully (threads=%d)", nThreads);
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_nexus_companion_tts_TtsJni_generateSpeech(
    JNIEnv *env, jobject /* this */,
    jstring text, jstring speakerId
) {
    std::lock_guard<std::mutex> lock(g_tts_mutex);
    g_tts_abort = false;

    if (!g_tts_model || !g_tts_ctx || !g_vocoder_model || !g_vocoder_ctx) {
        LOGE("TTS models not loaded");
        return env->NewFloatArray(0);
    }

    const char *textStr = env->GetStringUTFChars(text, nullptr);
    const char *spkStr = env->GetStringUTFChars(speakerId, nullptr);
    std::string textCpp(textStr);
    std::string spkCpp(spkStr);
    env->ReleaseStringUTFChars(text, textStr);
    env->ReleaseStringUTFChars(speakerId, spkStr);

    LOGI("Generating speech for: %.50s... (speaker: %s)", textCpp.c_str(), spkCpp.c_str());

    // --- Step 1: Generate audio code tokens with OuteTTS ---

    // Build OuteTTS prompt
    // Format: <|text_start|>text<|text_end|><|audio_start|>
    std::string prompt = "<|text_start|>" + textCpp + "<|text_end|>\n<|audio_start|>\n";

    const llama_vocab *vocab = llama_model_get_vocab(g_tts_model);

    // Tokenize
    int n_tokens = prompt.size() + 64;
    std::vector<llama_token> tokens(n_tokens);
    n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                               tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                   tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    // Clear KV cache and process prompt
    llama_memory_clear(llama_get_memory(g_tts_ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_tts_ctx, batch) != 0) {
        LOGE("Failed to process TTS prompt");
        return env->NewFloatArray(0);
    }

    // Generate audio tokens
    // OuteTTS generates tokens that represent audio codes
    // Max ~30 seconds of audio (~750 frames at 24kHz with 32 tokens per frame)
    const int max_audio_tokens = 2048;
    std::vector<llama_token> audio_tokens;
    audio_tokens.reserve(max_audio_tokens);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    for (int i = 0; i < max_audio_tokens && !g_tts_abort; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_tts_ctx, -1);

        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }

        audio_tokens.push_back(tok);

        llama_batch next_batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_tts_ctx, next_batch) != 0) {
            break;
        }
    }

    llama_sampler_free(sampler);

    if (audio_tokens.empty()) {
        LOGI("No audio tokens generated");
        return env->NewFloatArray(0);
    }

    LOGI("Generated %zu audio tokens", audio_tokens.size());

    // --- Step 2: Decode audio tokens to PCM with vocoder ---

    llama_memory_clear(llama_get_memory(g_vocoder_ctx), true);

    llama_batch voc_batch = llama_batch_get_one(audio_tokens.data(), audio_tokens.size());
    if (llama_decode(g_vocoder_ctx, voc_batch) != 0) {
        LOGE("Vocoder decode failed");
        return env->NewFloatArray(0);
    }

    // Extract PCM samples from vocoder output embeddings
    int n_embd = llama_model_n_embd(g_vocoder_model);
    int n_outputs = audio_tokens.size();

    // The vocoder outputs are typically raw waveform samples
    // Get the output embeddings which contain audio samples
    const float *embd = llama_get_embeddings(g_vocoder_ctx);
    if (!embd) {
        LOGE("Failed to get vocoder embeddings");
        return env->NewFloatArray(0);
    }

    // Convert embeddings to PCM samples
    int n_samples = n_embd * n_outputs;
    std::vector<float> pcm(n_samples);

    for (int i = 0; i < n_samples; i++) {
        // Clamp to [-1.0, 1.0]
        float sample = embd[i];
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        pcm[i] = sample;
    }

    LOGI("Generated %d PCM samples (%.2f seconds)", n_samples,
         (float)n_samples / SAMPLE_RATE);

    // Return as Java float array
    jfloatArray result = env->NewFloatArray(n_samples);
    if (result) {
        env->SetFloatArrayRegion(result, 0, n_samples, pcm.data());
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_companion_tts_TtsJni_isTtsModelLoaded(
    JNIEnv *env, jobject /* this */
) {
    return (g_tts_model && g_tts_ctx && g_vocoder_model && g_vocoder_ctx)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nexus_companion_tts_TtsJni_unloadTtsModel(
    JNIEnv *env, jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_tts_mutex);
    if (g_vocoder_ctx) { llama_free(g_vocoder_ctx); g_vocoder_ctx = nullptr; }
    if (g_vocoder_model) { llama_model_free(g_vocoder_model); g_vocoder_model = nullptr; }
    if (g_tts_ctx) { llama_free(g_tts_ctx); g_tts_ctx = nullptr; }
    if (g_tts_model) { llama_model_free(g_tts_model); g_tts_model = nullptr; }
    LOGI("TTS models unloaded");
}

JNIEXPORT void JNICALL
Java_com_nexus_companion_tts_TtsJni_abortTts(
    JNIEnv *env, jobject /* this */
) {
    g_tts_abort = true;
}

} // extern "C"
