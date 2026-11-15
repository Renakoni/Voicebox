#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
struct Session {
    void* library = nullptr;
    const OrtApi* api = nullptr;
    OrtEnv* env = nullptr;
    OrtSessionOptions* options = nullptr;
    OrtSession* session = nullptr;
    OrtRunOptions* run = nullptr;
    OrtMemoryInfo* memory = nullptr;
    std::vector<std::string> names;
    std::vector<std::vector<int64_t>> shapes;
    std::vector<size_t> counts;
    size_t frames = 0;

    void check(OrtStatus* status) {
        if (!status) return;
        std::string message = api->GetErrorMessage(status);
        api->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
    ~Session() {
        if (api) {
            if (run) api->ReleaseRunOptions(run);
            if (session) api->ReleaseSession(session);
            if (options) api->ReleaseSessionOptions(options);
            if (memory) api->ReleaseMemoryInfo(memory);
            if (env) api->ReleaseEnv(env);
        }
        if (library) dlclose(library);
    }
};
std::string string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

// The QNN runtime owns ALL its Ort handles. Java/WebGPU OrtValues never cross this boundary.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_rinstel_genie_1tts_inference_V5QnnSession_create(
        JNIEnv* env, jclass, jstring runtime, jstring model, jstring cache, jstring performance, jint frames) {
    try {
        auto s = std::make_unique<Session>();
        if (frames != 512 && frames != 768 && frames != 1000) throw std::runtime_error("Unsupported HTP bucket");
        s->frames = frames;
        const auto directory = string(env, runtime);
        const auto modelPath = string(env, model);
        const auto cachePath = string(env, cache);
        const auto mode = string(env, performance);
        const auto dspPath = directory + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
        setenv("ADSP_LIBRARY_PATH", dspPath.c_str(), 1);
        s->library = dlopen((directory + "/libonnxruntime-qnn.so").c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!s->library) throw std::runtime_error(dlerror());
        auto getApi = reinterpret_cast<const OrtApiBase*(*)()>(dlsym(s->library, "OrtGetApiBase"));
        if (!getApi || !(s->api = getApi()->GetApi(ORT_API_VERSION))) throw std::runtime_error("QNN ORT API unavailable");
        s->check(s->api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "VoiceboxQNN", &s->env));
        s->check(s->api->CreateSessionOptions(&s->options));
        s->check(s->api->SetIntraOpNumThreads(s->options, 4));
        s->check(s->api->AddSessionConfigEntry(s->options, "session.intra_op.allow_spinning", "0"));
        s->check(s->api->SetSessionGraphOptimizationLevel(s->options, ORT_ENABLE_BASIC));
        s->check(s->api->AddFreeDimensionOverrideByName(s->options, "frames", frames));
        const auto backendPath = directory + "/libQnnHtp.so";
        const char* keys[] = {"backend_path", "enable_htp_fp16_precision", "htp_performance_mode",
                              "htp_graph_finalization_optimization_mode"};
        const char* values[] = {backendPath.c_str(), "1", "power_saver", "0"};
        s->check(s->api->SessionOptionsAppendExecutionProvider(s->options, "QNN", keys, values, 4));
        s->check(s->api->AddSessionConfigEntry(s->options, "session.disable_cpu_ep_fallback", "1"));
        const bool cached = std::ifstream(cachePath).good();
        if (!cached) {
            s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_enable", "1"));
            s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_embed_mode", "1"));
            s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_file_path", cachePath.c_str()));
        }
        s->check(s->api->CreateSession(s->env, (cached ? cachePath : modelPath).c_str(), s->options, &s->session));
        s->api->ReleaseSessionOptions(s->options); s->options = nullptr;
        s->check(s->api->CreateRunOptions(&s->run));
        s->check(s->api->AddRunConfigEntry(s->run, "qnn.htp_perf_mode", mode.c_str()));
        s->check(s->api->AddRunConfigEntry(s->run, "qnn.htp_perf_mode_post_run", "power_saver"));
        s->check(s->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &s->memory));
        OrtAllocator* allocator = nullptr;
        s->check(s->api->GetAllocatorWithDefaultOptions(&allocator));
        size_t count = 0;
        s->check(s->api->SessionGetInputCount(s->session, &count));
        for (size_t i = 0; i < count; ++i) {
            char* name = nullptr;
            s->check(s->api->SessionGetInputName(s->session, i, allocator, &name));
            s->names.emplace_back(name); allocator->Free(allocator, name);
            OrtTypeInfo* info = nullptr;
            s->check(s->api->SessionGetInputTypeInfo(s->session, i, &info));
            const OrtTensorTypeAndShapeInfo* tensor = nullptr;
            s->check(s->api->CastTypeInfoToTensorInfo(info, &tensor));
            size_t rank = 0, elements = 0;
            s->check(s->api->GetDimensionsCount(tensor, &rank));
            std::vector<int64_t> shape(rank);
            s->check(s->api->GetDimensions(tensor, shape.data(), rank));
            s->check(s->api->GetTensorShapeElementCount(tensor, &elements));
            s->shapes.push_back(shape); s->counts.push_back(elements);
            s->api->ReleaseTypeInfo(info);
        }
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_rinstel_genie_1tts_inference_V5QnnSession_inputNames(JNIEnv* env, jclass, jlong handle) {
    auto& s = *reinterpret_cast<Session*>(handle);
    auto result = env->NewObjectArray(s.names.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < s.names.size(); ++i) {
        auto name = env->NewStringUTF(s.names[i].c_str());
        env->SetObjectArrayElement(result, i, name); env->DeleteLocalRef(name);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rinstel_genie_1tts_inference_V5QnnSession_run(
        JNIEnv* env, jclass, jlong handle, jobjectArray buffers, jobject output) {
    auto& s = *reinterpret_cast<Session*>(handle);
    std::vector<OrtValue*> owned;
    OrtValue* result = nullptr;
    try {
        if (static_cast<size_t>(env->GetArrayLength(buffers)) != s.names.size()) throw std::runtime_error("QNN input count");
        std::vector<const char*> names;
        std::vector<const OrtValue*> inputs;
        for (size_t i = 0; i < s.names.size(); ++i) {
            auto buffer = env->GetObjectArrayElement(buffers, i);
            auto data = env->GetDirectBufferAddress(buffer);
            const auto count = env->GetDirectBufferCapacity(buffer);
            env->DeleteLocalRef(buffer);
            if (!data || count != static_cast<jlong>(s.counts[i])) throw std::runtime_error("QNN input buffer shape");
            OrtValue* tensor = nullptr;
            s.check(s.api->CreateTensorWithDataAsOrtValue(s.memory, data, s.counts[i] * sizeof(float),
                s.shapes[i].data(), s.shapes[i].size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor));
            owned.push_back(tensor); inputs.push_back(tensor); names.push_back(s.names[i].c_str());
        }
        const char* outputName = "velocity";
        s.check(s.api->Run(s.session, s.run, names.data(), inputs.data(), inputs.size(), &outputName, 1, &result));
        auto destination = env->GetDirectBufferAddress(output);
        if (!destination || env->GetDirectBufferCapacity(output) != static_cast<jlong>(100 * s.frames)) throw std::runtime_error("QNN output buffer shape");
        void* source = nullptr;
        s.check(s.api->GetTensorMutableData(result, &source));
        std::memcpy(destination, source, 100 * s.frames * sizeof(float));
    } catch (const std::exception& error) { fail(env, error); }
    if (result) s.api->ReleaseValue(result);
    for (auto tensor : owned) s.api->ReleaseValue(tensor);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rinstel_genie_1tts_inference_V5QnnSession_terminate(JNIEnv* env, jclass, jlong handle, jboolean cancel) {
    auto& s = *reinterpret_cast<Session*>(handle);
    try { s.check(cancel ? s.api->RunOptionsSetTerminate(s.run) : s.api->RunOptionsUnsetTerminate(s.run)); }
    catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rinstel_genie_1tts_inference_V5QnnSession_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
