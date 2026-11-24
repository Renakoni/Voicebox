#include <asm/hwcap.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/auxv.h>

/* This probe itself is baseline ARM64. The experimental GPU binary uses -march=armv8.6-a. */
JNIEXPORT jint JNICALL
Java_dev_renakoni_voicebox_model_qwen3_QwenDevices_probe(JNIEnv *env, jclass type, jstring path, jboolean v75) {
    const unsigned long required = HWCAP_ATOMICS | HWCAP_ASIMDDP | HWCAP_ASIMDRDM;
    const unsigned long required2 = HWCAP2_I8MM | HWCAP2_BF16;
    if ((getauxval(AT_HWCAP) & required) != required || (getauxval(AT_HWCAP2) & required2) != required2)
        return 0;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return 0;
    void *lib = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (!lib) return 0;

    void *(*init)(const char *, const char *) = dlsym(lib, "ggml_backend_init_by_name");
    void (*release)(void *) = dlsym(lib, "ggml_backend_free");
    int modes = 0;
    if (init && release) {
        void *gpu = init("Vulkan0", NULL);
        if (gpu) {
            release(gpu);
            modes = 1;
            void *htp = v75 ? init("HTP0", NULL) : NULL;
            if (htp) {
                release(htp);
                modes |= 2;
            }
        }
    }
    // The probe runs in a disposable worker. Keep GGML's process-wide registry loaded.
    return modes;
}
