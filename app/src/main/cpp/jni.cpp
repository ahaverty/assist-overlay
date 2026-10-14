// Minimal JNI bridge to whisper.cpp: load a model once, transcribe 16 kHz mono float samples to text.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "AssistWhisper", __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_ahaverty_assist_Whisper_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    LOG("loaded %s: %p", p, ctx);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ahaverty_assist_Whisper_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ahaverty_assist_Whisper_nativeTranscribe(JNIEnv *env, jclass, jlong ctxPtr, jfloatArray samples,
                                                  jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.initial_prompt = pr;
    params.audio_ctx = 0;

    std::string text;
    if (whisper_full(ctx, params, data, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (pr) env->ReleaseStringUTFChars(prompt, pr);
    return env->NewStringUTF(text.c_str());
}
