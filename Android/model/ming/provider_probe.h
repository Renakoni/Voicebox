#pragma once

#include <cmath>
#include <onnxruntime_cxx_api.h>

// Both providers run the same four-value affine graph with CPU fallback disabled.
inline bool run_provider_probe(const Ort::Env& env, const std::string& graph, Ort::SessionOptions& options) {
    options.SetIntraOpNumThreads(1);
    Ort::Session session(env, graph.c_str(), options);
    const int64_t shape[] = {1, 4};
    float input[] = {1.f, 2.f, 3.f, 4.f};
    auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    auto tensor = Ort::Value::CreateTensor<float>(memory, input, 4, shape, 2);
    const char* inputs[] = {"x"};
    const char* outputs[] = {"y"};
    auto result = session.Run(Ort::RunOptions{nullptr}, inputs, &tensor, 1, outputs, 1);
    auto info = result[0].GetTensorTypeAndShapeInfo();
    if (info.GetElementCount() != 4) return false;
    const auto* values = result[0].GetTensorData<float>();
    for (int i = 0; i < 4; ++i) {
        if (!std::isfinite(values[i]) || std::abs(values[i] - (input[i] * .5f + .25f)) >= .01f) return false;
    }
    return true;
}
