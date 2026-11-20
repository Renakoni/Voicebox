package dev.rinstel.genie_tts.inference

import dev.rinstel.genie_tts.R
import android.content.res.Resources
import dev.rinstel.genie_tts.i18n.UiText

fun modelArchitectureText(architecture: String): UiText = UiText(when (architecture) {
    "v2ProPlus" -> R.string.model_v2_pro_plus
    "v2Pro" -> R.string.model_v2_pro
    "v2" -> R.string.model_v2
    "v5turbo" -> R.string.model_v5_turbo
    "ming" -> R.string.ming_name
    "v5dev" -> R.string.model_v5_dev_unsupported
    "v5" -> R.string.model_v5_pending
    "lora" -> R.string.model_lora_unsupported
    else -> R.string.model_version_unsupported
})

fun modelArchitectureLabel(resources: Resources, architecture: String): String =
    modelArchitectureText(architecture).resolve(resources)
