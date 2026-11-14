package dev.rinstel.genie_tts.inference

enum class ExecutionBackend(val label: String) {
    CPU("CPU"),
    WEBGPU("CPU + GPU"),
}
