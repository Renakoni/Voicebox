package dev.renakoni.voicebox.bridge

enum class ExecutionBackend(val label: String) {
    CPU("CPU"),
    WEBGPU("CPU + GPU"),
}
