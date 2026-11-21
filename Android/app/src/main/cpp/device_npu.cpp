#include <android/NeuralNetworks.h>
#include <jni.h>

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
