#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <vector>
#include <cstring>
#include <atomic>
#include <mutex>
#include <algorithm>
#include "onnxruntime_c_api.h"
#include "native_semantic_slicing.h"

#define TAG "NativeDecoderLoop"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static const OrtApi* g_ort = nullptr;
static void* g_ortLibrary = nullptr;

using OrtGetApiBaseFn = const OrtApiBase* (*)();

static const OrtApi* getOrtApi() {
    if (g_ort) return g_ort;
    // The AAR ships ORT as a runtime library, not a linkable import
    // library. Resolve the C API after Java has loaded that same .so.
    g_ortLibrary = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_ortLibrary) {
        LOGE("dlopen(libonnxruntime.so) failed: %s", dlerror());
        return nullptr;
    }
    auto getApiBase = reinterpret_cast<OrtGetApiBaseFn>(
        dlsym(g_ortLibrary, "OrtGetApiBase")
    );
    if (!getApiBase) {
        LOGE("dlsym(OrtGetApiBase) failed: %s", dlerror());
        return nullptr;
    }
    const OrtApiBase* base = getApiBase();
    if (!base) { LOGE("OrtGetApiBase() returned null"); return nullptr; }
    g_ort = base->GetApi(ORT_API_VERSION);
    if (!g_ort) { LOGE("GetApi(%d) returned null", ORT_API_VERSION); return nullptr; }
    return g_ort;
}

struct DecoderCancellation {
    const OrtApi* api;
    std::atomic<bool> cancelled{false};
    std::mutex mutex;
    OrtRunOptions* options = nullptr;
    explicit DecoderCancellation(const OrtApi* value) : api(value) {}
};

extern "C" JNIEXPORT jlong JNICALL
Java_dev_renakoni_voicebox_model_gptsovits_NativeDecoderLoop_nativeCreateCancellation(JNIEnv*, jclass) {
    const OrtApi* api = getOrtApi();
    return api ? reinterpret_cast<jlong>(new DecoderCancellation(api)) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_renakoni_voicebox_model_gptsovits_NativeDecoderLoop_nativeCancel(JNIEnv*, jclass, jlong handle) {
    auto* cancellation = reinterpret_cast<DecoderCancellation*>(handle);
    std::lock_guard<std::mutex> lock(cancellation->mutex);
    cancellation->cancelled.store(true);
    if (cancellation->options) {
        OrtStatus* status = cancellation->api->RunOptionsSetTerminate(cancellation->options);
        if (status) cancellation->api->ReleaseStatus(status);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_renakoni_voicebox_model_gptsovits_NativeDecoderLoop_nativeDeleteCancellation(JNIEnv*, jclass, jlong handle) {
    // Kotlin detaches the cancellation hook after native execution has returned.
    delete reinterpret_cast<DecoderCancellation*>(handle);
}

static const char* jstrToCstr(JNIEnv* env, jstring jstr) {
    return env->GetStringUTFChars(jstr, nullptr);
}

static void releaseJstr(JNIEnv* env, jstring jstr, const char* cstr) {
    env->ReleaseStringUTFChars(jstr, cstr);
}

static jlongArray extractTokensFromOutput(JNIEnv* env, const OrtApi* api, OrtValue* yValue, int generatedSteps) {
    if (!yValue || generatedSteps <= 0) return nullptr;

    void* yData = nullptr;
    OrtStatus* status = api->GetTensorMutableData(yValue, &yData);
    if (status) {
        LOGE("GetTensorMutableData(y) failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return nullptr;
    }

    OrtTensorTypeAndShapeInfo* shapeInfo = nullptr;
    status = api->GetTensorTypeAndShape(yValue, &shapeInfo);
    if (status) {
        LOGE("GetTensorTypeAndShape failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return nullptr;
    }

    size_t numElements = 0;
    status = api->GetTensorShapeElementCount(shapeInfo, &numElements);
    api->ReleaseTensorTypeAndShapeInfo(shapeInfo);
    if (status) {
        LOGE("GetTensorShapeElementCount failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return nullptr;
    }

    int64_t* yTokens = reinterpret_cast<int64_t*>(yData);
    // Clamp to the actual tensor length so early/failed stops do not underflow.
    size_t tokenCount = (size_t)generatedSteps < numElements ? (size_t)generatedSteps : numElements;
    size_t startIdx = numElements - tokenCount;

    std::vector<int64_t> tokens(yTokens + startIdx, yTokens + startIdx + tokenCount);
    const std::vector<int64_t> semanticTokens =
        SliceSemanticTokens(tokens, generatedSteps, 0);
    jlongArray result = env->NewLongArray((jsize)semanticTokens.size());
    if (!result || semanticTokens.empty()) return result;
    env->SetLongArrayRegion(result, 0, (jsize)semanticTokens.size(),
        reinterpret_cast<const jlong*>(semanticTokens.data()));
    return result;
}

static void notifyDecoderProgress(
    JNIEnv* env,
    jobject progressCallback,
    jmethodID progressMethod,
    int generatedSteps,
    int maxSteps
) {
    if (!progressCallback || !progressMethod) return;
    env->CallVoidMethod(progressCallback, progressMethod, (jint)generatedSteps, (jint)maxSteps);
    if (env->ExceptionCheck()) {
        LOGE("Decoder progress callback threw; suppressing progress callback failure");
        env->ExceptionClear();
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_renakoni_voicebox_model_gptsovits_NativeDecoderLoop_nativeDecoderLoop(
    JNIEnv* env, jclass cls,
    jlong sessionPtr,
    jobjectArray inputNamesJ,
    jobjectArray outputNamesJ,
    jlongArray yDataJ, jlongArray yShapeJ,
    jfloatArray yEmbDataJ, jlongArray yEmbShapeJ,
    jfloatArray kvDataJ, jlongArray kvShapeJ,
    jint numKvTensors,
    jintArray outputMappingJ,
    jfloatArray samplingJ,
    jint maxSteps,
    jobject progressCallback,
    jlong cancellationHandle
) {
    (void)cls;

    auto* cancellation = reinterpret_cast<DecoderCancellation*>(cancellationHandle);
    if (!cancellation || cancellation->cancelled.load()) return nullptr;

    const OrtApi* api = getOrtApi();
    if (!api || !sessionPtr) {
        LOGE("ORT API or session is null");
        return nullptr;
    }

    OrtSession* session = reinterpret_cast<OrtSession*>(sessionPtr);
    int numInputs = env->GetArrayLength(inputNamesJ);
    int numOutputs = env->GetArrayLength(outputNamesJ);
    jmethodID progressMethod = nullptr;
    if (progressCallback) {
        jclass callbackClass = env->GetObjectClass(progressCallback);
        progressMethod = env->GetMethodID(callbackClass, "onProgress", "(II)V");
        env->DeleteLocalRef(callbackClass);
        if (!progressMethod && env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    // --- Convert input/output names to C strings ---
    std::vector<const char*> inputNames(numInputs);
    std::vector<const char*> outputNames(numOutputs);
    std::vector<jstring> inputJStrs(numInputs);
    std::vector<jstring> outputJStrs(numOutputs);

    for (int i = 0; i < numInputs; i++) {
        inputJStrs[i] = (jstring) env->GetObjectArrayElement(inputNamesJ, i);
        inputNames[i] = jstrToCstr(env, inputJStrs[i]);
    }
    for (int i = 0; i < numOutputs; i++) {
        outputJStrs[i] = (jstring) env->GetObjectArrayElement(outputNamesJ, i);
        outputNames[i] = jstrToCstr(env, outputJStrs[i]);
    }

    // --- Get output mapping ---
    jint* mapping = env->GetIntArrayElements(outputMappingJ, nullptr);

    // Keep all acquired JNI/ORT resources in one cleanup path. Inference errors
    // are expected on device while probing provider capabilities, so an error
    // must not leave pinned Java arrays or native tensors behind.
    jlong* yShapeArr = nullptr;
    jlong* yDataRaw = nullptr;
    jlong* yEmbShapeArr = nullptr;
    jfloat* yEmbDataRaw = nullptr;
    jlong* kvShapeArr = nullptr;
    jfloat* kvDataRaw = nullptr;
    OrtMemoryInfo* cpuMemInfo = nullptr;
    OrtRunOptions* runOptions = nullptr;
    auto releaseRunOptions = [&]() {
        std::lock_guard<std::mutex> lock(cancellation->mutex);
        cancellation->options = nullptr;
        if (runOptions) api->ReleaseRunOptions(runOptions);
        runOptions = nullptr;
    };
    OrtValue* yValue = nullptr;
    OrtValue* yEmbValue = nullptr;
    std::vector<OrtValue*> kvValues(numKvTensors, nullptr);
    // A request owns these capacity buffers. Cancel/retry/voice changes never
    // reuse partially updated KV. Only the first two inputs evolve as outputs.
    int cacheLengthIndex = -1;
    int samplingIndex = -1;
    for (int i = 0; i < numInputs; ++i) {
        if (std::strcmp(inputNames[i], "cache_length") == 0) cacheLengthIndex = i;
        if (std::strcmp(inputNames[i], "sampling") == 0) samplingIndex = i;
    }
    const bool sharedCache = cacheLengthIndex >= 0;
    float sampling[4];
    OrtValue* samplingValue = nullptr;
    std::vector<std::vector<float>> sharedKv;
    int32_t cacheLength = 0;
    OrtValue* cacheLengthValue = nullptr;

    auto releaseJavaResources = [&]() {
        if (yShapeArr) {
            env->ReleaseLongArrayElements(yShapeJ, yShapeArr, JNI_ABORT);
            yShapeArr = nullptr;
        }
        if (yDataRaw) {
            env->ReleaseLongArrayElements(yDataJ, yDataRaw, JNI_ABORT);
            yDataRaw = nullptr;
        }
        if (yEmbShapeArr) {
            env->ReleaseLongArrayElements(yEmbShapeJ, yEmbShapeArr, JNI_ABORT);
            yEmbShapeArr = nullptr;
        }
        if (yEmbDataRaw) {
            env->ReleaseFloatArrayElements(yEmbDataJ, yEmbDataRaw, JNI_ABORT);
            yEmbDataRaw = nullptr;
        }
        if (kvShapeArr) {
            env->ReleaseLongArrayElements(kvShapeJ, kvShapeArr, JNI_ABORT);
            kvShapeArr = nullptr;
        }
        if (kvDataRaw) {
            env->ReleaseFloatArrayElements(kvDataJ, kvDataRaw, JNI_ABORT);
            kvDataRaw = nullptr;
        }
        if (mapping) {
            env->ReleaseIntArrayElements(outputMappingJ, mapping, JNI_ABORT);
            mapping = nullptr;
        }
        for (int i = 0; i < numInputs; ++i) {
            if (inputJStrs[i]) {
                if (inputNames[i]) releaseJstr(env, inputJStrs[i], inputNames[i]);
                env->DeleteLocalRef(inputJStrs[i]);
                inputJStrs[i] = nullptr;
                inputNames[i] = nullptr;
            }
        }
        for (int i = 0; i < numOutputs; ++i) {
            if (outputJStrs[i]) {
                if (outputNames[i]) releaseJstr(env, outputJStrs[i], outputNames[i]);
                env->DeleteLocalRef(outputJStrs[i]);
                outputJStrs[i] = nullptr;
                outputNames[i] = nullptr;
            }
        }
    };

    auto releaseOrtResources = [&]() {
        if (samplingValue) {
            api->ReleaseValue(samplingValue);
            samplingValue = nullptr;
        }
        if (yValue) {
            api->ReleaseValue(yValue);
            yValue = nullptr;
        }
        if (yEmbValue) {
            api->ReleaseValue(yEmbValue);
            yEmbValue = nullptr;
        }
        for (OrtValue*& value : kvValues) {
            if (value) {
                api->ReleaseValue(value);
                value = nullptr;
            }
        }
        if (cacheLengthValue) {
            api->ReleaseValue(cacheLengthValue);
            cacheLengthValue = nullptr;
        }
        if (runOptions) {
            releaseRunOptions();
        }
        if (cpuMemInfo) {
            api->ReleaseMemoryInfo(cpuMemInfo);
            cpuMemInfo = nullptr;
        }
    };

    auto fail = [&]() -> jlongArray {
        releaseOrtResources();
        releaseJavaResources();
        return nullptr;
    };

    // --- Create memory info ---
    if (samplingIndex < 0 || numInputs != numKvTensors + 3 + (sharedCache ? 1 : 0)) {
        LOGE("Unexpected decoder input contract");
        return fail();
    }
    OrtStatus* status = api->CreateCpuMemoryInfo(
        OrtDeviceAllocator, OrtMemTypeDefault, &cpuMemInfo);
    if (status) {
        LOGE("CreateCpuMemoryInfo failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return fail();
    }

    if (env->GetArrayLength(samplingJ) != 4) return fail();
    env->GetFloatArrayRegion(samplingJ, 0, 4, sampling);
    if (env->ExceptionCheck()) return fail();
    int64_t samplingShape[] = {4};
    status = api->CreateTensorWithDataAsOrtValue(cpuMemInfo, sampling, sizeof(sampling),
        samplingShape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &samplingValue);
    if (status) {
        LOGE("Create sampling controls failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return fail();
    }
    // --- Create run options ---
    status = api->CreateRunOptions(&runOptions);
    if (status) {
        LOGE("CreateRunOptions failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return fail();
    }

    // --- Create initial state tensors ---
    {
        std::lock_guard<std::mutex> lock(cancellation->mutex);
        cancellation->options = runOptions;
    }
    if (cancellation->cancelled.load()) return fail();

    int yShapeLen = env->GetArrayLength(yShapeJ);
    yShapeArr = env->GetLongArrayElements(yShapeJ, nullptr);
    int yDataLen = env->GetArrayLength(yDataJ);
    yDataRaw = env->GetLongArrayElements(yDataJ, nullptr);
    if (!yShapeArr || !yDataRaw) {
        LOGE("Failed to access initial y arrays");
        return fail();
    }

    status = api->CreateTensorWithDataAsOrtValue(
        cpuMemInfo, yDataRaw, yDataLen * sizeof(int64_t),
        yShapeArr, yShapeLen, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &yValue);
    if (status) {
        LOGE("CreateTensor y failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return fail();
    }

    int yEmbShapeLen = env->GetArrayLength(yEmbShapeJ);
    yEmbShapeArr = env->GetLongArrayElements(yEmbShapeJ, nullptr);
    int yEmbDataLen = env->GetArrayLength(yEmbDataJ);
    yEmbDataRaw = env->GetFloatArrayElements(yEmbDataJ, nullptr);
    if (!yEmbShapeArr || !yEmbDataRaw) {
        LOGE("Failed to access initial y_emb arrays");
        return fail();
    }

    status = api->CreateTensorWithDataAsOrtValue(
        cpuMemInfo, yEmbDataRaw, yEmbDataLen * sizeof(float),
        yEmbShapeArr, yEmbShapeLen, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &yEmbValue);
    if (status) {
        LOGE("CreateTensor y_emb failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return fail();
    }

    int kvShapeLen = env->GetArrayLength(kvShapeJ);
    kvShapeArr = env->GetLongArrayElements(kvShapeJ, nullptr);
    kvDataRaw = env->GetFloatArrayElements(kvDataJ, nullptr);
    if (!kvShapeArr || !kvDataRaw) {
        LOGE("Failed to access initial KV arrays");
        return fail();
    }

    size_t kvPerTensor = 1;
    for (int i = 0; i < kvShapeLen; i++) {
        kvPerTensor *= (size_t)kvShapeArr[i];
    }
    size_t kvBytesPerTensor = kvPerTensor * sizeof(float);

    int64_t sharedShape[4] = {1, 16, 0, 32};
    if (sharedCache) {
        if (kvShapeLen != 3 || kvShapeArr[0] != 16 || kvShapeArr[2] != 32 ||
            kvShapeArr[1] <= 0 || maxSteps <= 0 || numKvTensors != 48 || numOutputs != 51) {
            LOGE("Invalid shared FP32 decoder cache contract");
            return fail();
        }
        cacheLength = static_cast<int32_t>(kvShapeArr[1]);
        sharedShape[2] = static_cast<int64_t>(cacheLength) + std::min(maxSteps, 256);
        sharedKv.resize(numKvTensors);
        int64_t lengthShape[] = {1};
        status = api->CreateTensorWithDataAsOrtValue(cpuMemInfo, &cacheLength, sizeof(cacheLength),
            lengthShape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &cacheLengthValue);
        if (status) {
            LOGE("Create cache length failed: %s", api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return fail();
        }
        LOGI("shared_kv capacity=%lld initial=%d append_bytes_per_step=%zu",
            static_cast<long long>(sharedShape[2]), cacheLength, numKvTensors * 16 * 32 * sizeof(float));
    }

    for (int i = 0; i < numKvTensors; i++) {
        void* dataPtr = kvDataRaw + (i * kvPerTensor);
        if (sharedCache) {
            auto& buffer = sharedKv[i];
            buffer.resize(static_cast<size_t>(16 * sharedShape[2] * 32));
            for (int head = 0; head < 16; ++head) {
                std::memcpy(buffer.data() + head * sharedShape[2] * 32,
                    kvDataRaw + i * kvPerTensor + head * cacheLength * 32,
                    static_cast<size_t>(cacheLength) * 32 * sizeof(float));
            }
            dataPtr = buffer.data();
        }
        status = api->CreateTensorWithDataAsOrtValue(
            cpuMemInfo, dataPtr, sharedCache ? sharedKv[i].size() * sizeof(float) : kvBytesPerTensor,
            sharedCache ? sharedShape : kvShapeArr, sharedCache ? 4 : kvShapeLen,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &kvValues[i]);
        if (status) {
            LOGE("CreateTensor kv[%d] failed: %s", i, api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return fail();
        }
    }

    // --- Build initial input array ---
    std::vector<OrtValue*> inputs(numInputs);
    inputs[0] = yValue;
    inputs[1] = yEmbValue;
    for (int i = 0; i < numKvTensors; i++) {
        inputs[2 + i] = kvValues[i];
    }
    if (sharedCache) inputs[cacheLengthIndex] = cacheLengthValue;
    inputs[samplingIndex] = samplingValue;

    int generatedSteps = 0;
    bool stopped = false;
    // =====================================================================
    // Array-based Run with shared KV buffers
    // =====================================================================
    {
        std::vector<OrtValue*> outputs(numOutputs, nullptr);
        std::vector<OrtValue*> prevOutputs(numOutputs, nullptr);
        const int ownedOutputs = sharedCache ? 3 : numOutputs;
        bool runFailed = false;

        for (int step = 0; step < maxSteps && !stopped && !cancellation->cancelled.load(); step++) {
            if (sharedCache && cacheLength == sharedShape[2]) {
                // Grow in bounded chunks instead of reserving maxSteps at once.
                // Copy history only at a capacity boundary, never every token.
                const int64_t previousCapacity = sharedShape[2];
                sharedShape[2] += std::min(maxSteps - step, 256);
                for (int i = 0; i < numKvTensors; ++i) {
                    std::vector<float> grown(static_cast<size_t>(16 * sharedShape[2] * 32));
                    for (int head = 0; head < 16; ++head) {
                        std::memcpy(grown.data() + head * sharedShape[2] * 32,
                            sharedKv[i].data() + head * previousCapacity * 32,
                            static_cast<size_t>(cacheLength) * 32 * sizeof(float));
                    }
                    OrtValue* value = nullptr;
                    status = api->CreateTensorWithDataAsOrtValue(cpuMemInfo, grown.data(), grown.size() * sizeof(float),
                        sharedShape, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value);
                    if (status) {
                        LOGE("Grow shared KV failed: %s", api->GetErrorMessage(status));
                        api->ReleaseStatus(status);
                        runFailed = true;
                        break;
                    }
                    api->ReleaseValue(kvValues[i]);
                    sharedKv[i].swap(grown);
                    inputs[2 + i] = kvValues[i] = value;
                }
                if (runFailed) break;
                LOGI("shared_kv_grow capacity=%lld at_step=%d", static_cast<long long>(sharedShape[2]), step);
            }
            for (int i = 0; i < numOutputs; i++) {
                outputs[i] = sharedCache && i >= 3 ? kvValues[i - 3] : nullptr;
            }

            status = api->Run(
                session, runOptions,
                inputNames.data(), (const OrtValue* const*)inputs.data(), (size_t)numInputs,
                outputNames.data(), (size_t)numOutputs, outputs.data());

            if (status) {
                LOGE("Run failed at step %d: %s", step, api->GetErrorMessage(status));
                api->ReleaseStatus(status);
                runFailed = true;
                break;
            }

            generatedSteps++;
            if (sharedCache) cacheLength++;
            notifyDecoderProgress(env, progressCallback, progressMethod, generatedSteps, maxSteps);

            // stop_condition is a CPU scalar in the array-based path.
            void* stopData = nullptr;
            if (numOutputs <= 2 || !outputs[2]) {
                LOGE("Array Run returned no stop_condition output at step %d", step);
                runFailed = true;
                break;
            }
            status = api->GetTensorMutableData(outputs[2], &stopData);
            if (status) {
                LOGE("GetTensorMutableData(stop) failed: %s", api->GetErrorMessage(status));
                api->ReleaseStatus(status);
                runFailed = true;
                break;
            }
            bool stop = *reinterpret_cast<bool*>(stopData);
            if (stop) {
                stopped = true;
                break;
            }

            // Release outputs from two iterations ago after inputs no longer reference them.
            if (step > 0) {
                for (int i = 0; i < ownedOutputs; i++) {
                    if (prevOutputs[i]) {
                        api->ReleaseValue(prevOutputs[i]);
                        prevOutputs[i] = nullptr;
                    }
                }
            }

            // Transfer ownership from outputs to prevOutputs for the next decoder step.
            for (int i = 0; i < ownedOutputs; i++) {
                prevOutputs[i] = outputs[i];
                // Prevent cleanup from releasing the same OrtValue twice if maxSteps is reached.
                outputs[i] = nullptr;
            }

            // Map model outputs back to the next step inputs.
            for (int i = 0; i < (sharedCache ? 2 : numInputs); i++) {
                if (mapping[i] >= 0) inputs[i] = prevOutputs[mapping[i]];
            }
        }

        // If the loop stopped immediately, outputs[0] still owns the final y tensor.
        OrtValue* finalY = outputs[0] ? outputs[0] : prevOutputs[0];
        // Do not turn a provider/runtime error after an earlier successful step
        // into a shorter, apparently valid utterance.
        jlongArray result = runFailed
            ? nullptr
            : extractTokensFromOutput(env, api, finalY, generatedSteps);

        // Cleanup tensors and Java resources.
        for (int i = 0; i < ownedOutputs; i++) {
            if (outputs[i]) api->ReleaseValue(outputs[i]);
            if (prevOutputs[i]) api->ReleaseValue(prevOutputs[i]);
        }

        releaseOrtResources();
        releaseJavaResources();

        return result;
    }
}
