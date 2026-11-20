package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Bundle
import dalvik.system.DexClassLoader
import dev.renakoni.voicebox.bridge.*
import java.io.File

internal fun BridgePackage.openProvider(context: Context, directory: File, work: File, settings: Bundle): BackendProvider {
    val loader = DexClassLoader(File(directory, spec.code).absolutePath, context.codeCacheDir.absolutePath,
        directory.absolutePath, BackendEntry::class.java.classLoader)
    val entry = loader.loadClass(spec.entry).asSubclass(BackendEntry::class.java)
    check(entry.classLoader === loader) { "Backend entry must come from its own package" }
    return entry.getDeclaredConstructor().newInstance().open(context, directory, work, settings).also {
        require(it.id == spec.id) { "Backend identity differs from the installed catalog" }
    }
}

/** Metadata can be inspected locally. Native sessions are created only in the private service. */
class InstalledBackend(private val context: Context, private val archive: BridgePackage,
    private val settings: () -> Bundle = { Bundle() }) : BackendProvider {
    override val id get() = archive.spec.id
    private var cached: Pair<File, BackendProvider>? = null
    @Synchronized private fun metadata(): BackendProvider? {
        val directory = archive.directory ?: return null
        cached?.takeIf { it.first == directory }?.let { return it.second }
        return archive.openProvider(context, directory, context.cacheDir, settings()).also { cached = directory to it }
    }
    override val capabilities get() = metadata()?.capabilities ?: BackendCapabilities(emptyList(), emptySet(), emptySet())
    override val supportsWarmup get() = metadata()?.supportsWarmup == true
    override fun languageError(language: String, text: String) = metadata()?.languageError(language, text)
    override fun discoverCharacterModels() = metadata()?.discoverCharacterModels().orEmpty()
    override fun inspect(model: CharacterModel) = requireNotNull(metadata()).inspect(model)
    override fun findReferenceExample(model: CharacterModel) = metadata()?.findReferenceExample(model)
    override fun supportsAuxiliaryReferences(model: CharacterModel) = metadata()?.supportsAuxiliaryReferences(model) == true
    override fun executionModes() = metadata()?.executionModes().orEmpty()
    override fun validateOptions(options: Map<String, String>) = requireNotNull(metadata()).validateOptions(options)
    override fun createRuntime(execution: ExecutionBackend): BackendRuntime =
        WorkerRuntime(context, id, execution, settings(), supportsWarmup)
}
