#include <jni.h>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

int ming_cli_main(int argc, char** argv);
extern char** environ;

static std::string utf8(JNIEnv* env, jstring value) {
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}

// Runs only in VoiceBox's private backend worker, never in the UI/ORT process.
extern "C" JNIEXPORT jint JNICALL BRIDGE_METHOD(
    JNIEnv* env, jclass, jobjectArray arguments, jobjectArray variables, jstring log_path) {
    std::vector<std::string> keys;
    for (char** item = environ; *item; ++item) {
        std::string key(*item, std::string(*item).find('='));
        if (key.rfind("MING_", 0) == 0 || key == "ADSP_LIBRARY_PATH") keys.push_back(key);
    }
    for (const auto& key : keys) unsetenv(key.c_str());
    for (jsize i = 0; i < env->GetArrayLength(variables); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(variables, i));
        auto variable = utf8(env, value);
        env->DeleteLocalRef(value);
        auto equal = variable.find('=');
        if (equal != std::string::npos) setenv(variable.substr(0, equal).c_str(), variable.substr(equal + 1).c_str(), 1);
    }
    std::vector<std::string> values;
    for (jsize i = 0; i < env->GetArrayLength(arguments); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        values.push_back(utf8(env, value));
        env->DeleteLocalRef(value);
    }
    std::vector<char*> argv;
    for (auto& value : values) argv.push_back(value.data());
    argv.push_back(nullptr);
    std::ofstream log(utf8(env, log_path));
    if (!log) return -1;
    auto* out = std::cout.rdbuf(log.rdbuf());
    auto* err = std::cerr.rdbuf(log.rdbuf());
    int code;
    try { code = ming_cli_main(static_cast<int>(values.size()), argv.data()); }
    catch (const std::exception& error) { log << error.what(); code = -1; }
    std::cout.rdbuf(out);
    std::cerr.rdbuf(err);
    return code;
}

#ifdef VOICEBOX_GPU_PROBE
#include <algorithm>
#include <onnxruntime_cxx_api.h>

int ming_gpu_probe_main(int argc, char** argv);

extern "C" JNIEXPORT jint JNICALL Java_dev_renakoni_voicebox_model_ming_MingWebGpu_probe(
    JNIEnv* env, jclass, jstring graph, jstring input, jstring output) {
    try {
        const auto providers = Ort::GetAvailableProviders();
        if (std::find(providers.begin(), providers.end(), "WebGpuExecutionProvider") == providers.end()) return 2;
    } catch (const Ort::Exception&) { return 1; }
    std::string files[]{"ming-gpu-probe", utf8(env, graph), utf8(env, input), utf8(env, output)};
    char* argv[]{files[0].data(), files[1].data(), files[2].data(), files[3].data(), nullptr};
    return ming_gpu_probe_main(4, argv);
}
#endif
