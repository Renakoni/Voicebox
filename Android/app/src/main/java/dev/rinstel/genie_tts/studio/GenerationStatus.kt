package dev.rinstel.genie_tts.studio

import dev.rinstel.genie_tts.R
import android.content.res.Resources

import dev.rinstel.genie_tts.BackendServiceState
import dev.rinstel.genie_tts.inference.GenerationStage

internal data class GenerationStatus(val title: String, val detail: String? = null, val percent: Int? = null)

/** Legacy progressPercent is a stage estimate. Only counted bytes have a known denominator. */
internal fun generationStatus(state: BackendServiceState, resources: Resources): GenerationStatus {
    val label = state.progressLabel.orEmpty()
    Regex("Unpacking weights (\\d+)/(\\d+)").matchEntire(label)?.let {
        val done = it.groupValues[1].toLong()
        val total = it.groupValues[2].toLong()
        if (total > 0) {
            val percent = (done.coerceIn(0, total) * 100 / total).toInt()
            return GenerationStatus(resources.getString(R.string.stage_unpacking), resources.getString(R.string.stage_file_percent, percent), percent)
        }
    }
    Regex("Decoder (\\d+)/(\\d+)").matchEntire(label)?.let {
        return GenerationStatus(resources.getString(R.string.stage_generating), resources.getString(R.string.stage_tokens, it.groupValues[1]))
    }
    Regex("NPU (compiling|loading) (\\d+)/(\\d+)").matchEntire(label)?.let {
        val completed = resources.getString(R.string.stage_completed_items, it.groupValues[2], it.groupValues[3])
        return if (it.groupValues[1] == "compiling") {
            GenerationStatus(resources.getString(R.string.stage_npu_compiling), resources.getString(R.string.stage_npu_first_compile, completed))
        } else GenerationStatus(resources.getString(R.string.stage_npu_loading), completed)
    }
    val title = when (label) {
        "Inspecting resources" -> resources.getString(R.string.stage_checking_model)
        "Preparing model" -> resources.getString(R.string.stage_preparing_weights)
        "Initializing backend" -> resources.getString(R.string.stage_loading_model)
        "Preparing runtime" -> resources.getString(R.string.stage_shared_models)
        "Text features" -> resources.getString(R.string.stage_text)
        "Reference conditioning", "Reference audio" -> resources.getString(R.string.stage_reference_audio)
        "HuBERT" -> resources.getString(R.string.stage_speaker_features)
        "Reference ready", "Prompt conditioning" -> resources.getString(R.string.stage_voice_features)
        "Features ready", "T2S encoder" -> resources.getString(R.string.stage_encoding)
        "First decoder" -> resources.getString(R.string.stage_first_token)
        "Decoder complete", "Vocoder" -> resources.getString(R.string.stage_waveform)
        "Vocoder complete", "Writing output", "Output ready" -> resources.getString(R.string.stage_saving)
        else -> when (state.stage) {
            GenerationStage.INSTALLING_MODEL -> resources.getString(R.string.stage_preparing_weights)
            GenerationStage.INITIALIZING_BACKEND -> resources.getString(R.string.stage_loading_model)
            GenerationStage.PREPARING_FEATURES -> resources.getString(R.string.stage_reference_voice)
            GenerationStage.RUNNING_INFERENCE -> resources.getString(R.string.stage_generating)
            GenerationStage.WRITING_OUTPUT -> resources.getString(R.string.stage_saving)
            else -> resources.getString(R.string.stage_preparing)
        }
    }
    return GenerationStatus(title)
}
