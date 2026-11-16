package dev.rinstel.genie_tts.studio

import dev.rinstel.genie_tts.BackendServiceState
import dev.rinstel.genie_tts.inference.GenerationStage

internal data class GenerationStatus(val title: String, val detail: String? = null, val percent: Int? = null)

/** Legacy progressPercent is a stage estimate. Only counted bytes have a known denominator. */
internal fun generationStatus(state: BackendServiceState): GenerationStatus {
    val label = state.progressLabel.orEmpty()
    Regex("Unpacking weights (\\d+)/(\\d+)").matchEntire(label)?.let {
        val done = it.groupValues[1].toLong()
        val total = it.groupValues[2].toLong()
        if (total > 0) {
            val percent = (done.coerceIn(0, total) * 100 / total).toInt()
            return GenerationStatus("正在展开权重", "当前文件 $percent%", percent)
        }
    }
    Regex("Decoder (\\d+)/(\\d+)").matchEntire(label)?.let {
        return GenerationStatus("正在生成语音", "已生成 ${it.groupValues[1]} 个语音 token")
    }
    Regex("NPU (compiling|loading) (\\d+)/(\\d+)").matchEntire(label)?.let {
        val completed = "已完成 ${it.groupValues[2]}/${it.groupValues[3]} 项"
        return if (it.groupValues[1] == "compiling") {
            GenerationStatus("正在编译 NPU 加速", "首次编译耗时较长，完成后自动缓存\n$completed")
        } else GenerationStatus("正在加载 NPU 缓存", completed)
    }
    val title = when (label) {
        "Inspecting resources" -> "正在检查模型资源"
        "Preparing model" -> "正在准备音色权重"
        "Initializing backend" -> "正在加载推理模型"
        "Preparing runtime" -> "正在准备公共模型"
        "Text features" -> "正在解析文字"
        "Reference conditioning", "Reference audio" -> "正在解析参考录音"
        "HuBERT" -> "正在提取说话人特征"
        "Reference ready", "Prompt conditioning" -> "正在准备音色特征"
        "Features ready", "T2S encoder" -> "正在编码语音条件"
        "First decoder" -> "正在生成首个语音 token"
        "Decoder complete", "Vocoder" -> "正在合成音频波形"
        "Vocoder complete", "Writing output", "Output ready" -> "正在保存音频"
        else -> when (state.stage) {
            GenerationStage.INSTALLING_MODEL -> "正在准备音色权重"
            GenerationStage.INITIALIZING_BACKEND -> "正在加载推理模型"
            GenerationStage.PREPARING_FEATURES -> "正在解析参考声音"
            GenerationStage.RUNNING_INFERENCE -> "正在生成语音"
            GenerationStage.WRITING_OUTPUT -> "正在保存音频"
            else -> "正在准备生成"
        }
    }
    return GenerationStatus(title)
}
