package dev.renakoni.voicebox.inference

data class ModelInspectionResult(
    val missingFiles: List<String>,
) {
    val isComplete: Boolean
        get() = missingFiles.isEmpty()
}
