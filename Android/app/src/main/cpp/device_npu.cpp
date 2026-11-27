#include <android/NeuralNetworks.h>
#include <dlfcn.h>
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_dev_renakoni_voicebox_core_DeviceFacts_queryHexagonArch(JNIEnv*, jclass) {
    void* rpc = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!rpc) return 0;
    using Control = int (*)(uint32_t, void*, uint32_t);
    auto control = reinterpret_cast<Control>(dlsym(rpc, "remote_handle_control"));
    // Qualcomm FastRPC remote.h: query ARCH_VER (6) on CDSP_DOMAIN_ID (3).
    struct { uint32_t domain, attribute, capability; } query{3, 6, 0};
    constexpr uint32_t getDspInfo = 2;
    // FastRPC AEE_EUNSUPPORTED, returned with or without DSP_AEE_EOFFSET.
    constexpr uint32_t unsupported = 0x14;
    const uint32_t status = control ? control(getDspInfo, &query, sizeof(query)) : unsupported;
    dlclose(rpc);
    if (status == unsupported || status == 0x80000400u + unsupported) return 0;
    if (status != 0) return -1;
    const uint32_t arch = query.capability & 0xff;
    return (arch & 15) <= 9 && (arch >> 4) <= 9 ? (arch >> 4) * 10 + (arch & 15) : 0;
}

static int queryAccelerator(void* nnapi) {
    auto getCount = reinterpret_cast<decltype(&ANeuralNetworks_getDeviceCount)>(dlsym(nnapi, "ANeuralNetworks_getDeviceCount"));
    auto getDevice = reinterpret_cast<decltype(&ANeuralNetworks_getDevice)>(dlsym(nnapi, "ANeuralNetworks_getDevice"));
    auto getType = reinterpret_cast<decltype(&ANeuralNetworksDevice_getType)>(dlsym(nnapi, "ANeuralNetworksDevice_getType"));
    uint32_t count = 0;
    if (!getCount || !getDevice || !getType || getCount(&count) != ANEURALNETWORKS_NO_ERROR) return -1;
    int result = 0;
    for (uint32_t i = 0; i < count; ++i) {
        ANeuralNetworksDevice* device = nullptr;
        int32_t type = ANEURALNETWORKS_DEVICE_UNKNOWN;
        if (getDevice(i, &device) != ANEURALNETWORKS_NO_ERROR ||
            getType(device, &type) != ANEURALNETWORKS_NO_ERROR || type == ANEURALNETWORKS_DEVICE_UNKNOWN) {
            result = -1;
        } else if (type == ANEURALNETWORKS_DEVICE_ACCELERATOR) {
            return 1;
        }
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_renakoni_voicebox_core_DeviceFacts_queryAndroidAccelerator(JNIEnv*, jclass) {
    // NNAPI is optional. Its absence must not prevent querying Qualcomm FastRPC.
    void* nnapi = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (!nnapi) return 0;
    const int result = queryAccelerator(nnapi);
    dlclose(nnapi);
    return result;
}
