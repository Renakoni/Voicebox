package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.openBackendAsset

import dev.renakoni.voicebox.core.ModelDownload
import dev.renakoni.voicebox.core.ModelDownloadFile
import dev.renakoni.voicebox.core.downloadModelFiles
import dev.renakoni.voicebox.core.deleteFileTree

import dev.renakoni.voicebox.core.i18n.UiText

import dev.renakoni.voicebox.bridge.ModelRef
import android.content.Context
import dev.renakoni.voicebox.model.gptsovits.checkpoint.CheckpointImporter
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files

data class OfficialModel(val id: String, val name: String, val architecture: String, val sovits: ModelDownload) {
    val ref get() = ModelRef("gptsovits", architecture, id)
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
    private fun JSONObject.download(): ModelDownload {
        val list = getJSONArray("urls")
        return ModelDownload(list.getString(0), getLong("bytes"), getString("sha256"),
            (1 until list.length()).map(list::getString))
    }
}

internal class OfficialModelInstaller(private val root: File, private val catalog: OfficialModelCatalog,
    private val shared: GptSharedResources, private val openAsset: (String) -> InputStream) {
    fun install(model: OfficialModel, cancel: () -> Unit, progress: (UiText, Int?) -> Unit) {
        val repository = GptModelRepository(root, openAsset)
        val sources = File(root, ".gpt-packages/voices")
        val stage = File(sources, model.id)
        val inputs = mutableListOf(File(sources, "${model.id}.pth"))
        // Older flat downloads shared one GPT checkpoint. Keep it while another voice still has staged inputs.
        val siblingStaged = catalog.models.any { it.id != model.id &&
            (File(sources, "${it.id}.pth").exists() || File(sources, ".${it.id}.pth.download").exists()) }
        if (!siblingStaged) inputs.add(File(sources, "s1v3.ckpt"))
        val leftovers = inputs.flatMap { listOf(it, File(sources, ".${it.name}.download"), shared.files.receipt(it)) }
        check(leftovers.map { it.parentFile!! }.distinct().all { it.canonicalFile == it.absoluteFile })
        cancel()
        leftovers.forEach { Files.deleteIfExists(it.toPath()) }
        deleteFileTree(stage)
        shared.forVoice(model.architecture, cancel, progress)
        if (!repository.inspect(model.requiredFiles).isComplete) {
            val gpt = ModelDownloadFile(catalog.gpt, File(stage, "s1v3.ckpt"))
            val sovits = ModelDownloadFile(model.sovits, File(stage, "${model.id}.pth"))
            progress(UiText("download_selecting_source"), null)
            downloadModelFiles(listOf(gpt, sovits), cancel) { done, count, percent ->
                progress(UiText("download_resources", done, count), percent)
            }
            cancel()
            progress(UiText("download_verified"), null)
            CheckpointImporter(root, openAsset).import(model.name,
                stageGpt = { gpt.target }, stageSovits = { sovits.target },
                progress = { progress(it, null) }, checkCancelled = cancel,
                newCharacterId = model.id,
                gptFilename = "s1v3.ckpt", sovitsFilename = if (model.architecture == "v5turbo") "s2Gv5turbo.pth" else "s2Gv2ProPlus.pth",
                architectureHint = model.architecture)
            deleteFileTree(stage)
        }
    }
}
