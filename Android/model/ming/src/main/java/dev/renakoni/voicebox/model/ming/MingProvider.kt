package dev.renakoni.voicebox.model.ming

import android.content.Context
import android.os.Bundle
import dev.renakoni.voicebox.bridge.*
import java.io.File

class MingEntry : BackendEntry {
    override fun open(context: Context, directory: File, work: File, settings: Bundle): BackendProvider =
        MingProvider(context, directory, work)
}

internal class MingProvider(private val context: Context, private val directory: File, private val work: File) : BackendProvider {
    override val id = "ming"
    override val capabilities = BackendCapabilities(listOf("zh", "ja", "en"), emptySet())
    override val supportsWarmup = false
    private val resources = MingModelResources(context)
    override fun discoverCharacterModels() = if (resources.available) listOf(MingModel.model) else emptyList()
    override fun inspect(model: CharacterModel) = ModelInspectionResult(if (resources.available) emptyList() else listOf("ming-model"))
    override fun findReferenceExample(model: CharacterModel) = resources.reference()
    override fun supportsAuxiliaryReferences(model: CharacterModel) = false
    override fun executionModes() = if (MingWebGpu.available(directory, work)) ExecutionBackend.entries.toSet()
        else setOf(ExecutionBackend.CPU)
    override fun createRuntime(execution: ExecutionBackend) = MingBackendRuntime(context, execution, directory, work)
    override fun validateOptions(options: Map<String, String>): Map<String, String> {
        require(options.isEmpty()) { "Ming does not accept synthesis parameters." }
        return emptyMap()
    }
}
