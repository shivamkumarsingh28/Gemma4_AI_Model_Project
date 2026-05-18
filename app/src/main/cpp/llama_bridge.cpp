#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::atomic<bool> g_should_stop{false};
static std::mutex g_mutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_gemma4app_LlamaBridge_loadModel(
        JNIEnv* env, jobject, jstring modelPath, jint nCtx, jint nThreads, jint nBatch) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap     = true;
    mparams.use_mlock    = false;

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx             = (uint32_t)nCtx;
    cparams.n_batch           = (uint32_t)nBatch;
    cparams.n_threads         = nThreads;
    cparams.n_threads_batch   = nThreads;

    g_ctx = llama_init_from_model(g_model, cparams);
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_gemma4app_LlamaBridge_generateStreaming(
        JNIEnv* env, jobject, jstring prompt, jint maxTokens,
        jfloat temp, jfloat topP, jint topK, jfloat penalty, jobject callback) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return;

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    g_should_stop = false;

    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);

    // Determine number of tokens first
    int n_tokens_est = llama_tokenize(vocab, promptStr, strlen(promptStr), nullptr, 0, true, false);
    std::vector<llama_token> tokens(abs(n_tokens_est) + 1);
    int n_tokens = llama_tokenize(vocab, promptStr, strlen(promptStr), tokens.data(), tokens.size(), true, false);
    env->ReleaseStringUTFChars(prompt, promptStr);

    if (n_tokens <= 0) return;
    tokens.resize(n_tokens);

    // Ensure we don't exceed context size
    uint32_t n_ctx = llama_n_ctx(g_ctx);
    if (tokens.size() > n_ctx - 4) {
        tokens.erase(tokens.begin(), tokens.begin() + (tokens.size() - (n_ctx - 4)));
    }

    llama_memory_seq_rm(llama_get_memory(g_ctx), -1, -1, -1);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) return;

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    for (int i = 0; i < maxTokens; i++) {
        if (g_should_stop) break;

        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256] = {};
        int n_piece = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n_piece > 0) {
            jstring jPiece = env->NewStringUTF(buf);
            env->CallVoidMethod(callback, onTokenMethod, jPiece);
            env->DeleteLocalRef(jPiece);
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }
    llama_sampler_free(smpl);
}

JNIEXPORT void JNICALL
Java_com_example_gemma4app_LlamaBridge_stopGeneration(JNIEnv*, jobject) {
    g_should_stop = true;
}

JNIEXPORT void JNICALL
Java_com_example_gemma4app_LlamaBridge_freeModel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
}

} // extern "C"
