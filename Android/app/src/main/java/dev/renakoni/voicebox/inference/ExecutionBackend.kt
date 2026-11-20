package dev.renakoni.voicebox.inference

enum class ExecutionBackend(val label: String) {
    CPU("CPU"),
    WEBGPU("CPU + GPU"),
}
