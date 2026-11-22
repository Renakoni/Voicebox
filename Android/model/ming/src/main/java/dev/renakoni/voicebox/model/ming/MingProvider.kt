package dev.renakoni.voicebox.model.ming

import android.content.Context
import android.os.Bundle
import dev.renakoni.voicebox.bridge.*
import java.io.File

class MingEntry : BackendEntry {
    override fun open(context: Context, directory: File, work: File, settings: Bundle): BackendProvider =
        MingProvider(context, directory, work, settings.getBoolean("allowNpu"))
}

internal class MingProvider(private val context: Context, private val directory: File, private val work: File,
    private val allowNpu: Boolean) : BackendProvider, SynthesisSettingsProvider, StreamingSynthesisProvider, VoicePresetProvider {
    override val id = "ming"
    override val capabilities = BackendCapabilities(listOf("zh", "ja", "en"), emptySet())
    override val synthesisSettingsLayout = SynthesisSettingsLayout.MING
    override val requiresOutputLanguage = false
    override val supportsWarmup = false
    private val resources = MingModelResources(context)
    private val gpuAvailable by lazy { MingWebGpu.available(directory) }
    override fun discoverCharacterModels() = if (resources.available) listOf(MingModel.model) else emptyList()
    override fun inspect(model: CharacterModel) = ModelInspectionResult(if (resources.available) emptyList() else listOf("ming-model"))
    override fun findReferenceExample(model: CharacterModel) = resources.reference()
    override fun voicePresets(model: CharacterModel) =
        if (model.ref == MingModel.model.ref) resources.voicePresets() else emptyList()
    override fun supportsAuxiliaryReferences(model: CharacterModel) = false
    override fun executionModes() = if (gpuAvailable) ExecutionBackend.entries.toSet()
        else setOf(ExecutionBackend.CPU)
    override fun accelerationInfo() = if (gpuAvailable) MingHtp.detect(context, directory) else null
    override fun createRuntime(execution: ExecutionBackend) =
        MingBackendRuntime(context, execution, directory, work, allowNpu)
    override fun validateOptions(options: Map<String, String>): Map<String, String> {
        val validated = MingSynthesisOptions.validate(options)
        validated["ip"]?.let { ip ->
            require(resources.voicePresets().any { it.id == ip }) { "Ming voice preset is unavailable for this model." }
        }
        return validated
    }

    override fun mergeSynthesisOptions(defaults: Map<String, String>, overrides: Map<String, String>) =
        MingSynthesisOptions.merge(defaults, overrides)

    override fun usesReference(options: Map<String, String>) = MingSynthesisOptions.usesReference(options)

    override fun usesOutputLanguage(options: Map<String, String>) = "dialect" !in options
}
