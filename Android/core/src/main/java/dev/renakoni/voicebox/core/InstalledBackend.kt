package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Bundle
import dalvik.system.DexClassLoader
import dev.renakoni.voicebox.bridge.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun InstalledBridge.openProvider(context: Context, work: File, settings: Bundle): BackendProvider {
    val loader = DexClassLoader(File(directory, code).absolutePath, context.codeCacheDir.absolutePath,
        directory.absolutePath, BackendEntry::class.java.classLoader)
    val entry = loader.loadClass(entry).asSubclass(BackendEntry::class.java)
    check(entry.classLoader === loader) { "Backend entry must come from its own package" }
    return entry.getDeclaredConstructor().newInstance().open(BackendAssetContext(context, File(directory, "assets")), directory, work, settings).also {
        require(it.id == id) { "Backend identity differs from the installed catalog" }
    }
}

/** Metadata can be inspected locally. Native sessions are created only in the private service. */
class InstalledBackend(private val context: Context, private val archive: BridgePackage,
    private val externalNpuRuntime: File? = null,
    private val settings: () -> Bundle = { Bundle() }) : BackendProvider, VoiceLibrary {
    override val id get() = archive.spec.id
    private var cached: BackendProvider? = null
    @Synchronized fun invalidateMetadata() {
        cached = null
    }
    @Synchronized private fun metadata(): BackendProvider? {
        cached?.let { return it }
        val installed = archive.active ?: return null
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

    private val gpuCache by lazy { ProbeCache(context, "$id-gpu") }
    private val npuCache by lazy { ProbeCache(context, "$id-npu") }

    private fun cachedModes(revision: String) = gpuCache.read(revision) { result ->
        val names = result.getJSONArray("modes")
        (0 until names.length()).map { ExecutionBackend.valueOf(names.getString(it)) }.toSet()
    }

    // An untested bridge must accept the first synthesis request so its GPU can be checked.
    val candidateModes get() = archive.active?.let {
        cachedModes(it.revision) ?: ExecutionBackend.entries.toSet()
    }.orEmpty()

    override fun executionModes(): Set<ExecutionBackend> {
        val installed = archive.active ?: return emptySet()
        cachedModes(installed.revision)?.let { return it }
        val result = WorkerRuntime(context, id, ExecutionBackend.WEBGPU, settings(), false).probe()
        val modes = requireNotNull(result.getStringArrayList("modes")).map(ExecutionBackend::valueOf).toSet()
            .takeIf { ExecutionBackend.WEBGPU in it }.orEmpty()
        gpuCache.write(installed.revision, JSONObject().put("modes", JSONArray(modes.map { it.name })))
        return modes
    }

    private fun npuCacheKey(): String {
        val revision = requireNotNull(archive.active).revision
        // The Qwen HTP bridge chooses its library from the architecture reported by FastRPC.
        if (id == "qwen3") return "$revision/${DeviceFacts.hexagonArch}"
        val directory = externalNpuRuntime ?: return revision
        val libraries = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "so" }.sortedBy { it.name }
        return revision + libraries.joinToString("\n", prefix = "\n") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    private fun probeNpu(key: String, options: Bundle): Boolean = try {
        val result = WorkerRuntime(context, id, ExecutionBackend.WEBGPU, options, false).probe(npu = true)
        check(result.containsKey("npu"))
        result.getBoolean("npu").also { npuCache.write(key, JSONObject().put("npu", it)) }
    } catch (error: Exception) {
        GenerationContext.checkCurrent()
        android.util.Log.w("VoiceBoxWorker", "NPU probe failed for $id", error)
        false
    }

    private fun configuredRuntime(execution: ExecutionBackend, probeIfMissing: Boolean): BackendRuntime? {
        val options = settings()
        val allowNpu = if (execution == ExecutionBackend.WEBGPU && options.getBoolean("allowNpu")) {
            val key = npuCacheKey()
            val cached = npuCache.read(key) { it.getBoolean("npu") }
            if (cached == null && !probeIfMissing) return null
            cached ?: probeNpu(key, options)
        } else false
        options.putBoolean("allowNpu", allowNpu)
        return WorkerRuntime(context, id, execution, options, retain = true)
    }

    fun createWarmupRuntime(execution: ExecutionBackend): BackendRuntime? =
        if (execution in archive.active?.let { cachedModes(it.revision) }.orEmpty()) configuredRuntime(execution, probeIfMissing = false) else null

    override fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext,
        progress: (String, Int?) -> Unit): Bundle =
        WorkerRuntime(this.context, id, ExecutionBackend.CPU, settings(), false).operate(action, arguments, context, progress)

    override fun validateOptions(options: Map<String, String>) = requireNotNull(metadata()).validateOptions(options)
    override fun createRuntime(execution: ExecutionBackend): BackendRuntime =
        checkNotNull(configuredRuntime(execution, probeIfMissing = true))
}
