package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.openBackendAsset

import dev.renakoni.voicebox.core.ModelDownload
import dev.renakoni.voicebox.core.OfficialDownloadState
import dev.renakoni.voicebox.core.ModelDownloadFile
import dev.renakoni.voicebox.core.downloadModelFiles

import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi

import dev.renakoni.voicebox.bridge.ModelRef
import android.content.Context
import dev.renakoni.voicebox.model.gptsovits.checkpoint.CheckpointImporter
import dev.renakoni.voicebox.model.gptsovits.checkpoint.DownloadedVocoder
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files

data class OfficialModel(val id: String, val name: String, val architecture: String, val sovits: ModelDownload) {
    val ref get() = ModelRef("gptsovits", architecture, id)
    val directory get() = "$architecture/$id/tts_models"
    val requiredFiles get() = when (architecture) {
        "v2ProPlus" -> RequiredModelFiles.v2ProPlusCharacter(id)
        "v5turbo" -> RequiredModelFiles.v5TurboCharacter(id)
        else -> error("Unsupported official model: $architecture")
    }
}

class OfficialModelCatalog(json: JSONObject) {
    constructor(context: Context) : this(context.openBackendAsset("official-models.json").use {
        JSONObject(it.bufferedReader().readText())
    })
    val gpt = json.getJSONObject("gpt").download()
    val vocoder = json.getJSONObject("vocoder").download()
    val runtime = json.getJSONArray("runtime").let { list -> List(list.length()) { index ->
        val item = list.getJSONObject(index)
        item.getString("path") to item.download()
    } }
    val languageFiles = json.getJSONArray("languageFrontends").let { list -> List(list.length()) { index ->
        val item = list.getJSONObject(index)
        item.getString("path") to item.download()
    } }
    val models = json.getJSONArray("models").let { list -> List(list.length()) { index ->
        val item = list.getJSONObject(index)
        OfficialModel(item.getString("id"), item.getString("name"), item.getString("architecture"),
            item.getJSONObject("sovits").download())
    } }
    fun vocoderReady(root: File): Boolean = listOf("v5-vocoder-subpixel.onnx", "v5_vocoder_fp32.bin")
        .all { File(root, "RuntimeAssets/v5-vocoder/$it").isFile }
    fun runtimeReady(root: File, variant: String): Boolean = RuntimeAssetRepository(root).inspect().isComplete && OfficialLanguageAssets.ready(root) &&
        (variant != "v5turbo" || vocoderReady(root))
    fun installed(root: File, model: OfficialModel): Boolean =
        GptModelRepository(root).inspect(model.requiredFiles).isComplete && runtimeReady(root, model.architecture)

    private fun JSONObject.download(): ModelDownload {
        val list = getJSONArray("urls")
        return ModelDownload(list.getString(0), getLong("bytes"), getString("sha256"),
            (1 until list.length()).map(list::getString))
    }
}

class OfficialModelInstaller(private val root: File, private val catalog: OfficialModelCatalog,
    private val openAsset: (String) -> InputStream) {
    fun install(model: OfficialModel, checkCancelled: () -> Unit, update: (OfficialDownloadState) -> Unit) {
        val stage = File(root, ".official-download")
        val languageBackup = File(stage, ".previous")
        if (languageBackup.isDirectory) {
            val target = File(root, "RuntimeAssets/language_frontends")
            if (!target.exists()) Files.move(languageBackup.toPath(), target.toPath())
        }
        if (stage.exists()) check(stage.deleteRecursively())
        check(stage.mkdirs())
        fun publish(label: UiText, percent: Int? = null) =
            update(OfficialDownloadState(model.ref, label, percent, running = true))
        try {
            val downloads = catalog.runtime.mapNotNull { (path, source) ->
                val target = File(root, "RuntimeAssets/$path")
                if (target.isFile && target.length() == source.bytes) null else ModelDownloadFile(source, target)
            }.toMutableList()
            val prepareLanguages = !OfficialLanguageAssets.ready(root)
            if (prepareLanguages) downloads += catalog.languageFiles.map { (name, source) -> ModelDownloadFile(source, File(stage, name)) }
            val vocoder = File(stage, "vocoder.pth").takeIf { model.architecture == "v5turbo" && !catalog.vocoderReady(root) }
            if (vocoder != null) downloads += ModelDownloadFile(catalog.vocoder, vocoder)
            val gpt = File(stage, "model.ckpt")
            val sovits = File(stage, "model.pth")
            downloads += ModelDownloadFile(catalog.gpt, gpt)
            downloads += ModelDownloadFile(model.sovits, sovits)
            publish(UiText("download_selecting_source"))
            downloadModelFiles(downloads, checkCancelled) { completed, total, percent ->
                publish(UiText("download_resources", completed, total), percent)
            }
            checkCancelled()
            if (prepareLanguages) OfficialLanguageAssets.install(root, stage, openAsset, checkCancelled)
            val hubertManifest = File(root, "RuntimeAssets/chinese-hubert-base/chinese-hubert-base_weights_fp16_manifest.json")
            if (!hubertManifest.isFile) openAsset("official-runtime/hubert-manifest.json").use { input ->
                hubertManifest.outputStream().use(input::copyTo)
            }
            if (vocoder != null) {
                publish(UiText("vocoder_converting"))
                DownloadedVocoder(root, openAsset).install(vocoder, checkCancelled)
                vocoder.delete()
            }
            checkCancelled()
            publish(UiText("download_verified"))
            val existing = File(root, "CharacterModels/${model.architecture}/${model.id}").isDirectory
            CheckpointImporter(root, openAsset).import(model.name,
                stageGpt = { Files.move(gpt.toPath(), it.toPath()) }, stageSovits = { Files.move(sovits.toPath(), it.toPath()) },
                progress = { publish(it) }, checkCancelled = checkCancelled,
                replace = model.ref.takeIf { existing }, newCharacterId = model.id.takeUnless { existing },
                gptFilename = "s1v3.ckpt", sovitsFilename = if (model.architecture == "v5turbo") "s2Gv5turbo.pth" else "s2Gv2ProPlus.pth",
                architectureHint = model.architecture)
            requireUi(catalog.installed(root, model)) {
                UiText("model_incomplete")
            }
        } finally { if (!languageBackup.exists()) stage.deleteRecursively() }
    }
}
