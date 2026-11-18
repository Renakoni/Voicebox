package dev.rinstel.genie_tts

import dev.rinstel.genie_tts.i18n.UiText
import dev.rinstel.genie_tts.i18n.requireUi

import android.content.Context
import android.content.res.Resources
import dev.rinstel.genie_tts.inference.modelArchitectureLabel
import dev.rinstel.genie_tts.checkpoint.CheckpointImporter
import dev.rinstel.genie_tts.checkpoint.DownloadedVocoder
import dev.rinstel.genie_tts.inference.ModelAssetRepository
import dev.rinstel.genie_tts.inference.RequiredModelFiles
import dev.rinstel.genie_tts.inference.RuntimeAssetRepository
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class ModelDownload(val url: String, val bytes: Long, val sha256: String, val mirrors: List<String> = emptyList())

data class OfficialModel(val id: String, val name: String, val architecture: String, val sovits: ModelDownload) {
    fun displayName(resources: Resources): String =
        resources.getString(R.string.official_model_name, modelArchitectureLabel(resources, architecture))

    val directory get() = "$architecture/$id/tts_models"
    val requiredFiles get() = when (architecture) {
        "v2ProPlus" -> RequiredModelFiles.v2ProPlusCharacter(id)
        "v5turbo" -> RequiredModelFiles.v5TurboCharacter(id)
        else -> error("Unsupported official model: $architecture")
    }
}

class OfficialModelCatalog(json: JSONObject) {
    constructor(context: Context) : this(context.assets.open("official-models.json").use {
        JSONObject(it.bufferedReader().readText())
    })
    val gpt = json.getJSONObject("gpt").download()
    val vocoder = json.getJSONObject("vocoder").download()
    val runtime = json.getJSONArray("runtime").let { list -> List(list.length()) { index ->
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
    fun runtimeReady(root: File, model: OfficialModel): Boolean = RuntimeAssetRepository(root).inspect().isComplete &&
        (model.architecture != "v5turbo" || vocoderReady(root))
    fun installed(root: File, model: OfficialModel): Boolean =
        ModelAssetRepository(root).inspect(model.requiredFiles).isComplete && runtimeReady(root, model)

    private fun JSONObject.download(): ModelDownload {
        val list = getJSONArray("urls")
        return ModelDownload(list.getString(0), getLong("bytes"), getString("sha256"),
            (1 until list.length()).map(list::getString))
    }
}

data class OfficialDownloadState(val modelId: String = "", val label: UiText? = null, val percent: Int? = null,
    val running: Boolean = false, val completed: Boolean = false, val error: UiText? = null)

class OfficialModelInstaller(private val root: File, private val catalog: OfficialModelCatalog,
    private val openAsset: (String) -> InputStream) {
    fun install(model: OfficialModel, checkCancelled: () -> Unit, update: (OfficialDownloadState) -> Unit) {
        val stage = File(root, ".official-download").apply { mkdirs() }
        fun publish(label: UiText, percent: Int? = null) =
            update(OfficialDownloadState(model.id, label, percent, running = true))
        try {
            val downloads = catalog.runtime.mapNotNull { (path, source) ->
                val target = File(root, "RuntimeAssets/$path")
                if (target.isFile && target.length() == source.bytes) null else ModelDownloadFile(source, target)
            }.toMutableList()
            val vocoder = File(stage, "vocoder.pth").takeIf { model.architecture == "v5turbo" && !catalog.vocoderReady(root) }
            if (vocoder != null) downloads += ModelDownloadFile(catalog.vocoder, vocoder)
            val gpt = File(stage, "model.ckpt")
            val sovits = File(stage, "model.pth")
            downloads += ModelDownloadFile(catalog.gpt, gpt)
            downloads += ModelDownloadFile(model.sovits, sovits)
            publish(UiText(R.string.download_selecting_source))
            downloadModelFiles(downloads, checkCancelled) { completed, total, percent ->
                publish(UiText(R.string.download_resources, completed, total), percent)
            }
            checkCancelled()
            val hubertManifest = File(root, "RuntimeAssets/chinese-hubert-base/chinese-hubert-base_weights_fp16_manifest.json")
            if (!hubertManifest.isFile) openAsset("official-runtime/hubert-manifest.json").use { input ->
                hubertManifest.outputStream().use(input::copyTo)
            }
            if (vocoder != null) {
                publish(UiText(R.string.vocoder_converting))
                DownloadedVocoder(root, openAsset).install(vocoder, checkCancelled)
                vocoder.delete()
            }
            checkCancelled()
            publish(UiText(R.string.download_verified))
            val existing = File(root, "CharacterModels/${model.architecture}/${model.id}").isDirectory
            CheckpointImporter(root, openAsset).import(model.name, { gpt.inputStream() }, { sovits.inputStream() },
                progress = { publish(it) }, checkCancelled = checkCancelled,
                replaceCharacterId = model.id.takeIf { existing }, newCharacterId = model.id.takeUnless { existing },
                gptFilename = "s1v3.ckpt", sovitsFilename = if (model.architecture == "v5turbo") "s2Gv5turbo.pth" else "s2Gv2ProPlus.pth",
                architectureHint = model.architecture)
            requireUi(catalog.installed(root, model)) {
                UiText(R.string.model_incomplete)
            }
        } finally { stage.deleteRecursively() }
    }
}
