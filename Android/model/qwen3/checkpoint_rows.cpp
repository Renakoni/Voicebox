#include "ggml.h"
#include <jni.h>
#include <cmath>

extern "C" JNIEXPORT void JNICALL
Java_dev_renakoni_voicebox_model_qwen3_checkpoint_QwenCheckpointConverter_convertRows(
        JNIEnv *env, jobject, jobject input, jobject scratch, jobject output,
        jint source, jint target, jint width, jint rows) {
    auto fail = [&](const char *message) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
    };
    if ((target != GGML_TYPE_F32 && target != GGML_TYPE_F16 && target != GGML_TYPE_Q4_K && target != GGML_TYPE_Q6_K)
            || (source != GGML_TYPE_F32 && source != GGML_TYPE_F16 && source != GGML_TYPE_BF16)
            || width <= 0 || rows <= 0 || width % ggml_blck_size((ggml_type) target) != 0) {
        return fail("Unsupported Qwen quantization layout");
    }
    const int64_t elements = (int64_t) rows * width;
    const auto bytes = ggml_row_size((ggml_type) target, width) * rows;
    if (elements > env->GetDirectBufferCapacity(scratch) / 4 ||
            elements > env->GetDirectBufferCapacity(input) / (source == GGML_TYPE_F32 ? 4 : 2) ||
            (int64_t) bytes > env->GetDirectBufferCapacity(output)) {
        return fail("Invalid Qwen conversion buffer");
    }
    void *raw = env->GetDirectBufferAddress(input);
    auto *floats = static_cast<float *>(env->GetDirectBufferAddress(scratch));
    void *packed = env->GetDirectBufferAddress(output);
    if (source == GGML_TYPE_BF16) {
        ggml_bf16_to_fp32_row(static_cast<const ggml_bf16_t *>(raw), floats, elements);
    } else if (source == GGML_TYPE_F16) {
        ggml_fp16_to_fp32_row(static_cast<const ggml_fp16_t *>(raw), floats, elements);
    } else {
        floats = static_cast<float *>(raw);
    }
    for (int64_t i = 0; i < elements; ++i) {
        if (!std::isfinite(floats[i])) return fail("Non-finite Qwen checkpoint weight");
        if (target == GGML_TYPE_F16 && std::fabs(floats[i]) > 65504.0f)
            return fail("Qwen checkpoint weight exceeds the FP16 range");
    }
    ggml_quantize_chunk((ggml_type) target, floats, packed, 0, rows, width, nullptr);
}
