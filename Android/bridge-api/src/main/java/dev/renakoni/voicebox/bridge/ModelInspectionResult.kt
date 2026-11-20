package dev.renakoni.voicebox.bridge

data class ModelInspectionResult(
    val missingFiles: List<String>,
) {
    val isComplete: Boolean
        get() = missingFiles.isEmpty()
}
