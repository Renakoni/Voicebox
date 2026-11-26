package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Bundle
import dalvik.system.DexClassLoader
import dev.renakoni.voicebox.bridge.*
import java.io.File

internal fun InstalledBridge.openProvider(context: Context, work: File, settings: Bundle): BackendProvider {
    val loader = DexClassLoader(File(directory, spec.code).absolutePath, context.codeCacheDir.absolutePath,
        directory.absolutePath, BackendEntry::class.java.classLoader)
    val entry = loader.loadClass(spec.entry).asSubclass(BackendEntry::class.java)
    check(entry.classLoader === loader) { "Backend entry must come from its own package" }
    return entry.getDeclaredConstructor().newInstance().open(BackendAssetContext(context, File(directory, "assets")), directory, work, settings).also {
        require(it.id == spec.id) { "Backend identity differs from the installed catalog" }
    }
}

/** Metadata can be inspected locally. Native sessions are created only in the private service. */
class InstalledBackend(private val context: Context, private val archive: BridgePackage,
    private val settings: () -> Bundle = { Bundle() }) : BackendProvider, VoiceLibrary {
    override val id get() = archive.spec.id
    private var cached: BackendProvider? = null
    @Synchronized fun invalidateMetadata() { cached = null }
    @Synchronized private fun metadata(): BackendProvider? {
        cached?.let { return it }
        val installed = archive.active ?: return null
        if (!archive.supported) return null
        return installed.openProvider(context, context.cacheDir, settings()).also { cached = it }
    }
    override val capabilities get() = metadata()?.capabilities ?: BackendCapabilities(emptyList(), emptySet())
    override val supportsWarmup get() = metadata()?.supportsWarmup == true
    override fun languageError(language: String, text: String) = metadata()?.languageError(language, text)
    override fun discoverCharacterModels() = metadata()?.discoverCharacterModels().orEmpty()
    override fun inspect(model: CharacterModel) = requireNotNull(metadata()).inspect(model)
    override fun findReferenceExample(model: CharacterModel) = metadata()?.findReferenceExample(model)
    override fun supportsAuxiliaryReferences(model: CharacterModel) = metadata()?.supportsAuxiliaryReferences(model) == true
    private fun library() = metadata() as? VoiceLibrary
    val hasVoiceLibrary get() = library() != null
    override fun recoverInstallations() { library()?.recoverInstallations() }
    override fun officialVoices() = library()?.officialVoices().orEmpty()
    override fun variantLabel(variant: String) = library()?.variantLabel(variant) ?: ""
    override fun resourcePacks(cancel: () -> Unit) = library()?.resourcePacks(cancel).orEmpty()
    override fun resourceHint(variant: String) = library()?.resourceHint(variant)
    override val controls get() = library()?.controls.orEmpty()
    override fun studioOptions(values: Map<String, String>) = library()?.studioOptions(values) ?: validateOptions(values)
    val synthesisSettingsLayout get() = (metadata() as? SynthesisSettingsProvider)?.synthesisSettingsLayout
    val supportsStreaming get() = metadata() is StreamingSynthesisProvider
    val streamSampleRate get() = (metadata() as StreamingSynthesisProvider).streamSampleRate
    fun referenceInput(model: CharacterModel) =
        (metadata() as? SynthesisSettingsProvider)?.referenceInput(model) ?: ReferenceInput.REQUIRED
    val requiresOutputLanguage get() = (metadata() as? SynthesisSettingsProvider)?.requiresOutputLanguage ?: true

    fun voicePresets(model: CharacterModel) = (metadata() as? VoicePresetProvider)?.voicePresets(model).orEmpty()

    fun usesReference(options: Map<String, String>) = (metadata() as? SynthesisSettingsProvider)?.usesReference(options) ?: true

    fun usesOutputLanguage(options: Map<String, String>) = (metadata() as? SynthesisSettingsProvider)?.usesOutputLanguage(options) ?: true

    fun resolveSynthesisOptions(defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String> =
        validateOptions((metadata() as? SynthesisSettingsProvider)?.mergeSynthesisOptions(defaults, overrides)
            ?: (defaults + overrides))

    @Volatile var npuAvailable = false
        private set
    override fun executionModes(): Set<ExecutionBackend> {
        if (!archive.installed) {
            npuAvailable = false
            return emptySet()
        }
        val modes = try {
            val result = WorkerRuntime(context, id, ExecutionBackend.CPU, settings(), false).probe()
            npuAvailable = result.getBoolean("npu")
            result.getStringArrayList("modes").orEmpty().map(ExecutionBackend::valueOf).toSet()
        } catch (error: Exception) {
            android.util.Log.w("VoiceBoxWorker", "Acceleration probe failed for $id", error)
            npuAvailable = false
            setOf(ExecutionBackend.CPU)
        }
        return if (archive.spec.requiresVulkan12Storage16 && ExecutionBackend.WEBGPU !in modes) emptySet() else modes
    }

    override fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext,
        progress: (String, Int?) -> Unit): Bundle =
        WorkerRuntime(this.context, id, ExecutionBackend.CPU, settings(), false).operate(action, arguments, context, progress)

    override fun validateOptions(options: Map<String, String>) = requireNotNull(metadata()).validateOptions(options)
    override fun createRuntime(execution: ExecutionBackend): BackendRuntime =
        WorkerRuntime(context, id, execution, settings().apply {
            putBoolean("allowNpu", getBoolean("allowNpu") && npuAvailable)
        }, retain = true)
}
