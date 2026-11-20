#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <vector>
extern "C" {
#include "SKP_Silk_SDK_API.h"
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_renakoni_voicebox_qqvoice_QqSilk_encode(JNIEnv* env, jobject, jshortArray pcm) {
    const auto fail = [&](const char* message) -> jbyteArray {
        env->ThrowNew(env->FindClass("java/io/IOException"), message);
        return nullptr;
    };
    const jsize count = env->GetArrayLength(pcm);
    if (count <= 0 || count > 24000 * 60) return fail("语音长度须在 0 到 60 秒之间");
    SKP_int32 size = 0;
    if (SKP_Silk_SDK_Get_Encoder_Size(&size) != 0 || size <= 0) return fail("SILK encoder size");
    std::vector<uint8_t> state(size);
    SKP_SILK_SDK_EncControlStruct control{};
    if (SKP_Silk_SDK_InitEncoder(state.data(), &control) != 0) return fail("SILK encoder init");
    control.API_sampleRate = 24000;
    control.maxInternalSampleRate = 24000;
    control.packetSize = 480;
    control.bitRate = 24000;
    control.complexity = 2;
    control.packetLossPercentage = 0;
    control.useInBandFEC = 0;
    control.useDTX = 0;
    std::vector<uint8_t> result{2, '#', '!', 'S', 'I', 'L', 'K', '_', 'V', '3'};
    for (jsize offset = 0; offset < count; offset += 480) {
        SKP_int16 frame[480]{};
        env->GetShortArrayRegion(pcm, offset, std::min<jsize>(480, count - offset), frame);
        if (env->ExceptionCheck()) return nullptr;
        SKP_uint8 payload[1250];
        SKP_int16 length = sizeof(payload);
        if (SKP_Silk_SDK_Encode(state.data(), &control, frame, 480, payload, &length) != 0 || length <= 0)
            return fail("SILK encode failed");
        result.push_back(static_cast<uint8_t>(length & 255));
        result.push_back(static_cast<uint8_t>((length >> 8) & 255));
        result.insert(result.end(), payload, payload + length);
    }
    jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size()));
    if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()),
                                     reinterpret_cast<const jbyte*>(result.data()));
    return output;
}
