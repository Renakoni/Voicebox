package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.ResourcePack
import dev.renakoni.voicebox.core.FilePin
import dev.renakoni.voicebox.core.ModelDownloadFile
import dev.renakoni.voicebox.core.PinnedModelFiles
import dev.renakoni.voicebox.core.deleteFileTree
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.pin
import dev.renakoni.voicebox.model.gptsovits.checkpoint.DownloadedVocoder
import dev.renakoni.voicebox.model.gptsovits.checkpoint.CharacterVoiceEditor
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class GptSharedResources(private val root: File, private val catalog: OfficialModelCatalog,
    private val openAsset: (String) -> InputStream) {
    private val runtime = File(root, "RuntimeAssets")
    private val cache = File(root, ".gpt-packages")
    val files = PinnedModelFiles(root, File(cache, "receipts"))
    private val outputs = openAsset("official-runtime/resources.lock.json").bufferedReader()
        .use { JSONObject(it.readText()).getJSONObject("outputs") }.let { entries ->
        entries.keys().asSequence().associateWith { name ->
            val pins = entries.getJSONArray(name)
            List(pins.length()) { i -> pins.getJSONObject(i).let { FilePin(it.getLong("bytes"), it.getString("sha256")) } }
        }
    }
    private val language = "language_frontends"
    private val bundled = mapOf(RuntimeAssetRepository.HUBERT_MANIFEST to "official-runtime/hubert-manifest.json",
        "$language/english/names.tsv" to "english-g2p/names.tsv", "$language/english/dictionary.tsv" to "english-g2p/dictionary.tsv")
    private val languageSources = catalog.languageFiles.toMap()
    private val raw = catalog.runtime.map { (name, source) -> ModelDownloadFile(source, File(runtime, name)) } +
        catalog.languageFiles.filter { it.first.startsWith("pos-") }.map { (name, source) ->
            ModelDownloadFile(source, File(runtime, "$language/english/$name"))
        }
    private val managed = outputs.keys + raw.map { it.target.relativeTo(runtime).invariantSeparatorsPath }
    private fun archive(name: String) = ModelDownloadFile(languageSources.getValue(name), File(cache, "languages/$name"))
    private val english = archive("checkpoint20.npz")
    private val japanese = archive("open_jtalk_dic_utf_8-1.11.tar.gz")
    private val commonDownloads = raw + listOf(english, japanese)
    private val vocoder = ModelDownloadFile(catalog.vocoder, File(cache, "vocoder.pth"))
    private val englishExternal = "$language/english/external"
    private val japaneseDir = "$language/japanese"
    private val englishOutputs = outputs.keys.filter { it.startsWith("$englishExternal/") }
    private val japaneseOutputs = outputs.keys.filter { it.startsWith("$japaneseDir/") }
    private val vocoderOutputs = outputs.keys.filter { it.startsWith("$VOCODER/") }

    fun importRuntime(input: InputStream, cancel: () -> Unit) {
        recoverRuntime()
        ResourcePackageImporter(root, managed, files).importRuntime(input, cancel)
    }

    fun recoverRuntime(id: String? = null) {
        ResourcePackageImporter.recover(root)
        if (id != VOCODER) for (path in listOf(englishExternal, japaneseDir)) {
            CharacterVoiceEditor.recoverReplacement(File(cache, "prepare/$path"), File(runtime, path))
        }
        if (id != COMMON) CharacterVoiceEditor.recoverReplacement(File(cache, "vocoder"), File(runtime, VOCODER))
    }

    private fun matchesRuntime(name: String, local: Map<String, FilePin>, cancel: () -> Unit,
        official: List<FilePin> = outputs.getValue(name)) =
        files.matches(File(runtime, name), local[name]?.let { official + it } ?: official, cancel)

    private fun recordOutput(name: String) {
        val file = File(runtime, name)
        files.record(file, outputs.getValue(name).first { it.bytes == file.length() })
    }

    private fun commonInstalled(local: Map<String, FilePin>, cancel: () -> Unit): Boolean {
        // Offline archives may contain self-contained English graphs instead of external weights.
        val graphs = if (File(runtime, englishExternal).isDirectory) englishOutputs else
            listOf("$language/english/encoder.onnx", "$language/english/decoder.onnx")
        return raw.all { file ->
            matchesRuntime(file.target.relativeTo(runtime).invariantSeparatorsPath, local, cancel, listOf(file.source.pin))
        } && bundled.keys.all { matchesRuntime(it, local, cancel) } && graphs.all { matchesRuntime(it, local, cancel) } &&
            japaneseOutputs.all { name ->
                (name == "$japaneseDir/COPYING" && name !in local && local.keys.any { it.startsWith("$japaneseDir/") }) ||
                    matchesRuntime(name, local, cancel)
            }
    }

    fun packs(cancel: () -> Unit): List<ResourcePack> {
        val local = ResourcePackageImporter.readPins(runtime)
        val commonReady = commonInstalled(local, cancel)
        val commonBytes = commonDownloads.sumOf { it.source.bytes }
        val vocoderReady = vocoderOutputs.all { matchesRuntime(it, local, cancel) }
        return listOf(
            ResourcePack(COMMON, "pack_gpt_common", commonBytes, if (commonReady) 0L else commonBytes, commonReady, emptySet()),
            ResourcePack(VOCODER, "pack_gpt_vocoder", vocoder.source.bytes,
                if (vocoderReady) 0L else vocoder.source.bytes, vocoderReady, setOf("v5turbo")),
        )
    }

    private fun buildDirectory(path: String, names: List<String>, cancel: () -> Unit, build: (File) -> Unit) {
        val target = File(runtime, path)
        val work = File(cache, "prepare/$path")
        val stage = File(work, "output")
        if (stage.exists()) check(stage.deleteRecursively())
        check(stage.mkdirs())
        try {
            build(stage)
            cancel()
            target.parentFile!!.mkdirs()
            if (target.exists()) CharacterVoiceEditor.replaceDirectory(stage, target)
            else Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            names.forEach(::recordOutput)
        } finally { stage.deleteRecursively() }
    }

    fun forVoice(variant: String, cancel: () -> Unit, progress: (UiText, Int?) -> Unit) =
        install(if (variant == "v5turbo") null else COMMON, cancel, progress)

    fun install(id: String?, cancel: () -> Unit, progress: (UiText, Int?) -> Unit) {
        require(id == null || id == COMMON || id == VOCODER)
        cancel()
        recoverRuntime(id)
        val local = ResourcePackageImporter.readPins(runtime)
        if (id != VOCODER && !commonInstalled(local, cancel)) {
            cancel()
            clearPack(COMMON)
            installCommon(cancel, progress)
        }
        if (id != COMMON && !vocoderOutputs.all { matchesRuntime(it, local, cancel) }) {
            cancel()
            clearPack(VOCODER)
            installVocoder(cancel, progress)
        }
    }

    fun remove(id: String) {
        require(id == COMMON || id == VOCODER)
        recoverRuntime(id)
        clearPack(id)
        if (id == COMMON) deleteFileTree(RuntimeAssetRepository(root).referenceAudioCacheDirectory())
    }

    private fun clearPack(id: String) {
        val common = id == COMMON
        val names = managed.filterTo(mutableSetOf()) { it.startsWith("$VOCODER/") != common }
        val downloads = if (common) commonDownloads.map { it.target } else listOf(vocoder.target)
        val managedFiles = names.mapTo(mutableSetOf()) { File(runtime, it) } + downloads
        val expandedWeights = File(runtime, RuntimeAssetRepository.HUBERT_FP32)
        val targets = managedFiles.flatMap { listOf(it, files.receipt(it)) } +
            downloads.map { File(it.parentFile, ".${it.name}.download") } +
            if (common) listOf(expandedWeights, File(expandedWeights.parentFile, ".${expandedWeights.name}.tmp"),
                File(cache, "derived/${expandedWeights.relativeTo(root).path}.stamp")) else emptyList()
        val trees = if (common) listOf(File(cache, "prepare/$englishExternal"), File(cache, "prepare/$japaneseDir"))
            else listOf(File(cache, "vocoder"))

        val parents = (targets + trees).asSequence().flatMap { target ->
            generateSequence(target.parentFile) { it.parentFile }.takeWhile { it != root }
        }.toSet()
        check(parents.none { Files.isSymbolicLink(it.toPath()) }) { "Resource directory is a symbolic link" }
        targets.forEach { Files.deleteIfExists(it.toPath()) }
        trees.forEach(::deleteFileTree)
        ResourcePackageImporter.removePins(runtime, names)
    }

    private fun installCommon(cancel: () -> Unit, progress: (UiText, Int?) -> Unit) {
        val title = UiText("pack_gpt_common")
        progress(title, null)
        files.downloadMissing(commonDownloads, cancel) { _, _, percent -> progress(title, percent) }
        for ((name, asset) in bundled) {
            cancel()
            val target = File(runtime, name)
            target.parentFile!!.mkdirs()
            val temp = File.createTempFile("asset-", ".tmp", target.parentFile)
            try {
                openAsset(asset).use { input -> temp.outputStream().use(input::copyTo) }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                recordOutput(name)
            } finally { temp.delete() }
        }
        progress(title, null)
        buildDirectory(englishExternal, englishOutputs, cancel) {
            OfficialLanguageAssets.buildEnglish(english.target, it, openAsset, cancel)
        }
        english.target.delete()
        buildDirectory(japaneseDir, japaneseOutputs, cancel) {
            OfficialLanguageAssets.buildJapanese(japanese.target, it, cancel)
        }
        japanese.target.delete()
    }

    private fun installVocoder(cancel: () -> Unit, progress: (UiText, Int?) -> Unit) {
        val title = UiText("pack_gpt_vocoder")
        progress(title, null)
        files.downloadMissing(listOf(vocoder), cancel) { _, _, percent -> progress(title, percent) }
        progress(UiText("vocoder_converting"), null)
        DownloadedVocoder(root, openAsset).install(vocoder.target, cancel)
        vocoderOutputs.forEach(::recordOutput)
        vocoder.target.delete()
    }

    companion object {
        const val COMMON = "common"
        const val VOCODER = "v5-vocoder"
    }
}
