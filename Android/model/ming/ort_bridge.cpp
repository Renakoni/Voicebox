#include <jni.h>
#include <dlfcn.h>
#include <onnxruntime_c_api.h>
#include <stdexcept>
#include <string>

namespace {
const OrtApiBase* runtime_api = nullptr;
std::string runtime_path;
}

// The upstream runner has static ORT objects. Select its API before loading it
// and keep that library alive for the lifetime of the private worker process.
void ming_select_ort(const std::string& path) {
    if (runtime_api) {
        if (path != runtime_path) throw std::runtime_error("Ming ORT is already selected");
        return;
    }
    void* library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!library) throw std::runtime_error(dlerror());
    auto getter = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(library, "OrtGetApiBase"));
    const auto* api = getter ? getter() : nullptr;
    if (!api || !api->GetApi(ORT_API_VERSION)) {
        dlclose(library);
        throw std::runtime_error("Ming ORT API is unavailable");
    }
    runtime_path = path;
    runtime_api = api;
}

extern "C" const OrtApiBase* ORT_API_CALL VoiceBoxMingOrtGetApiBase() noexcept {
    return runtime_api;
}

extern "C" JNIEXPORT void JNICALL Java_dev_ming_local_Native_select(JNIEnv* env, jclass, jstring path) {
    const char* bytes = env->GetStringUTFChars(path, nullptr);
    if (!bytes) return;
    try { ming_select_ort(bytes); }
    catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
    env->ReleaseStringUTFChars(path, bytes);
}
