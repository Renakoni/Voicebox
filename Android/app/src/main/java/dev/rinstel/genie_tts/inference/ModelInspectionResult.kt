package dev.rinstel.genie_tts.inference

data class ModelInspectionResult(
    val missingFiles: List<String>,
) {
    val isComplete: Boolean
        get() = missingFiles.isEmpty()
}
