package dev.renakoni.voicebox.model.ming

import dev.renakoni.voicebox.bridge.BackendProvider
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.ModelInspectionResult

import dev.renakoni.voicebox.bridge.BackendCapabilities
import android.content.Context

class MingProvider(private val context: Context) : BackendProvider {
    override val id = "ming"
    override val capabilities = BackendCapabilities(listOf("zh", "ja", "en"), emptySet(), emptySet())
    override val supportsWarmup = false
    private val runtime = MingPackageRepository(context)
    private val resources = MingModelResources(context)
    override fun discoverCharacterModels() = if (runtime.supported && runtime.installed && resources.available)
        listOf(MingModel.model) else emptyList()
    override fun inspect(model: CharacterModel) =
        ModelInspectionResult(if (runtime.installed && resources.available) emptyList() else listOf("ming-runtime"))
    override fun findReferenceExample(model: CharacterModel) = resources.reference()
    override fun supportsAuxiliaryReferences(model: CharacterModel) = false
    override fun executionModes() = if (runtime.supported && runtime.installed) ExecutionBackend.entries.toSet() else emptySet()
    override fun createRuntime(execution: ExecutionBackend) = MingBackendRuntime(context, execution)
    override fun validateOptions(options: Map<String, String>): Map<String, String> {
        require(options.isEmpty()) { "Ming does not accept synthesis parameters." }
        return emptyMap()
    }
}
