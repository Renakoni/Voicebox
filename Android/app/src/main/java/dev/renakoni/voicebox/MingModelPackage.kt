package dev.renakoni.voicebox

import android.content.Context
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.ResourcePack
import dev.renakoni.voicebox.core.DeviceFacts
import dev.renakoni.voicebox.core.ModelDownload
import dev.renakoni.voicebox.core.ModelDownloadFile
import dev.renakoni.voicebox.core.deleteFileTree
import dev.renakoni.voicebox.core.downloadModelFiles
import dev.renakoni.voicebox.core.regularFileAttributes
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

internal class MingModelPackage(context: Context) {
    private val context = context
    private val directory get() = context.getExternalFilesDir(null)?.resolve(MODEL_DIRECTORY)
    private val device = DeviceFacts.read(context)
    private val showHtp = (BuildConfig.DEBUG && BuildConfig.MODEL_PREVIEW) ||
        device.manufacturer.contains("qualcomm", ignoreCase = true) ||
        device.manufacturer.equals("qti", ignoreCase = true) || device.hardware.equals("qcom", ignoreCase = true)

    private val packs = context.assets.open("ming-models.json").bufferedReader().use {
        val catalog = JSONObject(it.readText())
        val baseUrl = catalog.getString("baseUrl")
        val groups = catalog.getJSONObject("packs")
        listOf(CLONE, HTP, OFFICIAL).associateWith { part ->
            val files = groups.getJSONObject(part)
            files.keys().asSequence().associateWith { path ->
                val file = files.getJSONObject(path)
                ModelDownload("$baseUrl/${file.getString("asset")}", file.getLong("bytes"), file.getString("sha256"))
            }
        }
    }

    val modelParts = if (showHtp) listOf(CLONE, HTP) else listOf(CLONE)

    fun modelPacks() = modelParts.map(::pack)
    fun officialPack() = pack(OFFICIAL)

    private fun pack(part: String): ResourcePack {
        val files = packs.getValue(part)
        val target = directory
        val bytes = files.values.sumOf { it.bytes }
        val installed = files.all { (path, source) -> target?.resolve(path)?.isInstalled(source) == true }
        return ResourcePack(part, "", bytes, if (installed) 0L else bytes, installed, emptySet())
    }

    private fun File.isInstalled(source: ModelDownload) = regularFileAttributes()?.size() == source.bytes

    fun install(part: String, checkCancelled: () -> Unit, progress: (Int) -> Unit) {
        val target = requireNotNull(directory)
        checkCancelled()
        val files = packs.getValue(part).map { (path, source) -> ModelDownloadFile(source, File(target, path)) }
        if (files.all { it.target.isInstalled(it.source) }) return
        uninstall(part)
        downloadModelFiles(files, checkCancelled) { _, _, percent -> progress(percent) }
    }

    fun uninstall(part: String) {
        val target = context.getExternalFilesDir(null)?.canonicalFile?.resolve(MODEL_DIRECTORY) ?: return
        val files = packs.getValue(part).keys.map(target::resolve)
        check(files.map { it.parentFile!! }.distinct().all { it.canonicalFile == it.absoluteFile })
        files.forEach { file ->
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(File(file.parentFile, ".${file.name}.download").toPath())
        }
        if (part == HTP) deleteFileTree(File(context.cacheDir, "ming-htp"))
    }

    companion object {
        private const val MODEL_DIRECTORY = "CharacterModels/ming/dense-0.5b"
        const val BACKEND = "ming"
        const val CLONE = "ming-clone"
        const val HTP = "ming-htp"
        const val OFFICIAL = "ming-official"
        val modelRef = ModelRef(BACKEND, "dense-0.5b", "ming-dense-0.5b")

        fun partTitle(part: String) = when (part) {
            CLONE -> R.string.ming_clone_resources
            HTP -> R.string.ming_htp_resources
            else -> R.string.ming_official_weights
        }
    }
}
