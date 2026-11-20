package dev.renakoni.voicebox.bridge

enum class VoiceImport { CHECKPOINT_PAIR, RUNTIME_ARCHIVE }

data class BackendCapabilities(
    val languages: List<String>,
    val imports: Set<VoiceImport>,
)

data class AccelerationInfo(val device: String = "", val available: Boolean = false, val detail: String = "npu_checking")

interface BackendProvider {
    val id: String
    val capabilities: BackendCapabilities
    fun languageError(language: String, text: String): String? = null
    val supportsWarmup: Boolean
    fun discoverCharacterModels(): List<CharacterModel>
    fun inspect(model: CharacterModel): ModelInspectionResult
    fun findReferenceExample(model: CharacterModel): ReferenceExample?
    fun supportsAuxiliaryReferences(model: CharacterModel): Boolean
    fun executionModes(): Set<ExecutionBackend>
    fun accelerationInfo(): AccelerationInfo? = null
    fun createRuntime(execution: ExecutionBackend): BackendRuntime
    fun validateOptions(options: Map<String, String>): Map<String, String>
}
