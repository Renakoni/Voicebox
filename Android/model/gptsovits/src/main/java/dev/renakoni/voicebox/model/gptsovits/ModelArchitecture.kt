package dev.renakoni.voicebox.model.gptsovits

import android.content.res.Resources
import dev.renakoni.voicebox.core.i18n.UiText

fun modelArchitectureText(architecture: String): UiText = UiText(when (architecture) {
    "v2ProPlus" -> "model_v2_pro_plus"
    "v2Pro" -> "model_v2_pro"
    "v2" -> "model_v2"
    "v5turbo" -> "model_v5_turbo"
    "ming" -> "ming_name"
    "v5dev" -> "model_v5_dev_unsupported"
    "v5" -> "model_v5_pending"
    "lora" -> "model_lora_unsupported"
    else -> "model_version_unsupported"
})

fun modelArchitectureLabel(resources: Resources, architecture: String): String =
    modelArchitectureText(architecture).resolve(resources)
