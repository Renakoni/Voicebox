#include <asm/hwcap.h>
#include <jni.h>
#include <sys/auxv.h>

JNIEXPORT jboolean JNICALL
Java_dev_renakoni_voicebox_model_qwen3_QwenDevices_hasInt8Kernels(JNIEnv *env, jclass type) {
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) && (getauxval(AT_HWCAP2) & HWCAP2_I8MM);
}
