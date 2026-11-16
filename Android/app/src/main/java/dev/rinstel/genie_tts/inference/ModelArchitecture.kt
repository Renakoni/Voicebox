package dev.rinstel.genie_tts.inference

fun modelArchitectureLabel(architecture: String): String = when (architecture) {
    "v2ProPlus" -> "V2 Pro Plus"
    "v2Pro" -> "V2 Pro"
    "v2" -> "V2"
    "v5turbo" -> "V5 Turbo"
    "v5dev" -> "V5 Dev · 暂不支持"
    "v5" -> "V5 · 导入时确认版本"
    "lora" -> "LoRA · 暂不支持"
    else -> "未支持的版本"
}
