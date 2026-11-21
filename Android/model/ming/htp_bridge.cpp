#include <jni.h>
#include <android/log.h>
#include <string>
#include "cpu_features.h"
#include "htp_options.h"
#include "provider_probe.h"

std::string ming_utf8(JNIEnv*, jstring);
void ming_select_ort(const std::string&);

static void initialize_htp(const std::string& directory) {
    ming_select_ort(directory + "/libonnxruntime-qnn.so");
    Ort::InitApi();
    setenv("ADSP_LIBRARY_PATH", (directory + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp").c_str(), 1);
    setenv("MING_QNN_BACKEND", (directory + "/libQnnHtp.so").c_str(), 1);
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_renakoni_voicebox_model_ming_MingHtp_probe(
    JNIEnv* env, jclass, jstring runtime, jstring graph) {
    if (!use_bf16_kernel(cpu_features(), false)) return JNI_FALSE;
    try {
        initialize_htp(ming_utf8(env, runtime));
        Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "ming-htp-probe");
        Ort::SessionOptions options;
        configure_htp(options, "balanced");
        return run_provider_probe(environment, ming_utf8(env, graph), options);
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, "MingWorker", "HTP probe: %s", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL Java_dev_renakoni_voicebox_model_ming_MingHtp_prepare(
    JNIEnv* env, jclass, jstring runtime, jstring source, jstring cache) {
    try {
        initialize_htp(ming_utf8(env, runtime));
        Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "ming-htp-prepare");
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(1);
        options.SetGraphOptimizationLevel(ORT_ENABLE_ALL);
        configure_htp(options, "balanced");
        options.AddConfigEntry("ep.context_enable", "1");
        options.AddConfigEntry("ep.context_embed_mode", "1");
        options.AddConfigEntry("ep.context_file_path", ming_utf8(env, cache).c_str());
        Ort::Session compiled(environment, ming_utf8(env, source).c_str(), options);
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
}
