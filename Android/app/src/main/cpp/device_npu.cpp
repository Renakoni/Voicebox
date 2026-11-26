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
    const bool ok = control && control(getDspInfo, &query, sizeof(query)) == 0;
    dlclose(rpc);
    const uint32_t arch = query.capability & 0xff;
    return ok && (arch & 15) <= 9 && (arch >> 4) <= 9 ? (arch >> 4) * 10 + (arch & 15) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_renakoni_voicebox_DeviceNpu_hasAndroidAccelerator(JNIEnv*, jclass) {
    uint32_t count = 0;
    if (ANeuralNetworks_getDeviceCount(&count) != ANEURALNETWORKS_NO_ERROR) return JNI_FALSE;
    for (uint32_t i = 0; i < count; ++i) {
        ANeuralNetworksDevice* device = nullptr;
        int32_t type = ANEURALNETWORKS_DEVICE_UNKNOWN;
        if (ANeuralNetworks_getDevice(i, &device) == ANEURALNETWORKS_NO_ERROR &&
            ANeuralNetworksDevice_getType(device, &type) == ANEURALNETWORKS_NO_ERROR &&
            type == ANEURALNETWORKS_DEVICE_ACCELERATOR) return JNI_TRUE;
    }
    return JNI_FALSE;
}
