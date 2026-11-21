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

    fun recoverRuntime() {
        ResourcePackageImporter.recover(root)
        for (path in listOf(englishExternal, japaneseDir)) {
            CharacterVoiceEditor.recoverReplacement(File(cache, "prepare/$path"), File(runtime, path))
        }
        CharacterVoiceEditor.recoverReplacement(File(cache, "vocoder"), File(runtime, "v5-vocoder"))
    }

    private fun matchesRuntime(name: String, local: Map<String, FilePin>, cancel: () -> Unit,
        official: List<FilePin> = outputs.getValue(name)) =
        files.matches(File(runtime, name), local[name]?.let { official + it } ?: official, cancel)

    private fun recordOutput(name: String) {
        val file = File(runtime, name)
        files.record(file, outputs.getValue(name).first { it.bytes == file.length() })
    }

    private data class CommonPlan(val downloads: List<ModelDownloadFile>, val bundledFiles: List<String>,
        val buildEnglish: Boolean, val buildJapanese: Boolean) {
        val installed get() = downloads.isEmpty() && bundledFiles.isEmpty() && !buildEnglish && !buildJapanese
    }

    private fun commonPlan(local: Map<String, FilePin>, cancel: () -> Unit): CommonPlan {
        // Offline archives may contain self-contained English graphs instead of external weights.
        val graphs = if (File(runtime, englishExternal).isDirectory) englishOutputs else
            listOf("$language/english/encoder.onnx", "$language/english/decoder.onnx")
        val buildEnglish = !graphs.all { matchesRuntime(it, local, cancel) }
        val buildJapanese = !japaneseOutputs.all { name ->
            (name == "$japaneseDir/COPYING" && name !in local && local.keys.any { it.startsWith("$japaneseDir/") }) ||
                matchesRuntime(name, local, cancel)
        }
        val sources = buildList {
            addAll(raw.filterNot { file ->
                matchesRuntime(file.target.relativeTo(runtime).invariantSeparatorsPath, local, cancel, listOf(file.source.pin))
            })
            if (buildEnglish && !files.matches(english.target, english.source.pin, cancel)) add(english)
            if (buildJapanese && !files.matches(japanese.target, japanese.source.pin, cancel)) add(japanese)
        }
        return CommonPlan(sources, bundled.keys.filterNot { matchesRuntime(it, local, cancel) }, buildEnglish, buildJapanese)
    }

    fun packs(cancel: () -> Unit): List<ResourcePack> {
        val local = ResourcePackageImporter.readPins(runtime)
        val common = commonPlan(local, cancel)
        val vocoderReady = vocoderOutputs.all { matchesRuntime(it, local, cancel) }
        return listOf(
            ResourcePack(COMMON, "pack_gpt_common",
                raw.sumOf { it.source.bytes } + english.source.bytes + japanese.source.bytes,
                common.downloads.sumOf { it.source.bytes }, common.installed, emptySet()),
            ResourcePack(VOCODER, "pack_gpt_vocoder",
                vocoder.source.bytes, if (vocoderReady || files.matches(vocoder.target, vocoder.source.pin, cancel)) 0 else vocoder.source.bytes,
                vocoderReady, setOf("v5turbo")),
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
        recoverRuntime()
        val local = ResourcePackageImporter.readPins(runtime)
        if (id != VOCODER) installCommon(local, cancel, progress)
        if (id != COMMON) installVocoder(local, cancel, progress)
    }

    fun remove(id: String) {
        require(id == COMMON || id == VOCODER)
        val common = id == COMMON
        val names = managed.filterTo(mutableSetOf()) { it.startsWith("$VOCODER/") != common }
        val downloads = if (common) raw.map { it.target } + listOf(english.target, japanese.target) else listOf(vocoder.target)
        val managedFiles = names.mapTo(mutableSetOf()) { File(runtime, it) } + downloads
        val expandedWeights = File(runtime, RuntimeAssetRepository.HUBERT_FP32)
        val targets = managedFiles.flatMap { listOf(it, files.receipt(it)) } +
            downloads.map { File(it.parentFile, ".${it.name}.download") } +
            if (common) listOf(expandedWeights, File(expandedWeights.parentFile, ".${expandedWeights.name}.tmp")) else emptyList()
        val trees = if (common) listOf(File(cache, "prepare"), File(cache, "derived"),
            RuntimeAssetRepository(root).referenceAudioCacheDirectory()) else listOf(File(cache, "vocoder"))

        // Resolve an interrupted archive import before deleting any files it could restore.
        ResourcePackageImporter.recover(root)
        val parents = (targets + trees).asSequence().flatMap { target ->
            generateSequence(target.parentFile) { it.parentFile }.takeWhile { it != root }
        }.toSet()
        check(parents.none { Files.isSymbolicLink(it.toPath()) }) { "Resource directory is a symbolic link" }
        targets.forEach { Files.deleteIfExists(it.toPath()) }
        trees.forEach(::deleteFileTree)
        ResourcePackageImporter.removePins(runtime, names)
    }

    private fun installCommon(local: Map<String, FilePin>, cancel: () -> Unit, progress: (UiText, Int?) -> Unit) {
        val plan = commonPlan(local, cancel)
        val title = UiText("pack_gpt_common")
        if (!plan.installed) progress(title, null)
        files.downloadMissing(plan.downloads, cancel) { _, _, percent -> progress(title, percent) }
        for (name in plan.bundledFiles) {
            cancel()
            val target = File(runtime, name)
            target.parentFile!!.mkdirs()
            val temp = File.createTempFile("asset-", ".tmp", target.parentFile)
            try {
                openAsset(bundled.getValue(name)).use { input -> temp.outputStream().use(input::copyTo) }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                recordOutput(name)
            } finally { temp.delete() }
        }
        if (plan.buildEnglish || plan.buildJapanese) progress(title, null)
        if (plan.buildEnglish) buildDirectory(englishExternal, englishOutputs, cancel) {
            OfficialLanguageAssets.buildEnglish(english.target, it, openAsset, cancel)
        }
        english.target.delete()
        if (plan.buildJapanese) buildDirectory(japaneseDir, japaneseOutputs, cancel) {
            OfficialLanguageAssets.buildJapanese(japanese.target, it, cancel)
        }
        japanese.target.delete()
    }

    private fun installVocoder(local: Map<String, FilePin>, cancel: () -> Unit, progress: (UiText, Int?) -> Unit) {
        if (!vocoderOutputs.all { matchesRuntime(it, local, cancel) }) {
            val title = UiText("pack_gpt_vocoder")
            progress(title, null)
            files.download(listOf(vocoder), cancel) { _, _, percent -> progress(title, percent) }
            progress(UiText("vocoder_converting"), null)
            DownloadedVocoder(root, openAsset).install(vocoder.target, cancel)
            vocoderOutputs.forEach(::recordOutput)
        }
        vocoder.target.delete()
    }

    companion object {
        const val COMMON = "common"
        const val VOCODER = "v5-vocoder"
    }
}
