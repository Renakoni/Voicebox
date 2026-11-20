package dev.renakoni.voicebox.model.gptsovits


internal fun modelArchitectureLabel(architecture: String): String = when (architecture) {
    "v2ProPlus" -> "model_v2_pro_plus"
    "v2Pro" -> "model_v2_pro"
    "v2" -> "model_v2"
    "v5turbo" -> "model_v5_turbo"
    "v5dev" -> "model_v5_dev_unsupported"
    "v5" -> "model_v5_pending"
    "lora" -> "model_lora_unsupported"
    else -> "model_version_unsupported"
}
