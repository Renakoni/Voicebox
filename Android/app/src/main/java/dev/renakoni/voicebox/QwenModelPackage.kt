package dev.renakoni.voicebox

import android.content.Context
import dev.renakoni.voicebox.bridge.ResourcePack
import dev.renakoni.voicebox.core.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class QwenModelPackage(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "qwen3-model")
    private val pins = PinnedModelFiles(root, File(root, "receipts"))
    private val files = context.assets.open("qwen3-models.json").bufferedReader().use {
        val catalog = JSONObject(it.readText())
        mapOf(CODEC to "qwen-tokenizer-12hz-F32.gguf", TALKER to "qwen-talker-0.6b-base-Q4_K_M.gguf").mapValues { (_, name) ->
            val item = catalog.getJSONObject(name)
            ModelDownloadFile(ModelDownload(item.getString("url"), item.getLong("bytes"), item.getString("sha256")), File(root, name))
        }
    }

    fun pack(part: String, cancel: () -> Unit): ResourcePack {
        val (source, target) = files.getValue(part)
        val installed = pins.matches(target, source.pin, cancel)
        return ResourcePack(part, if (part == CODEC) "qwen_audio_codec" else "ming_official_weights",
            source.bytes, if (installed) 0L else source.bytes, installed, emptySet())
    }

    fun install(part: String, cancel: () -> Unit, progress: (Int) -> Unit) {
        val (source, target) = files.getValue(part)
        if (pins.matches(target, source.pin, cancel)) return
        val temporary = File(root, ".${target.name}.download")
        try {
            stageModelFile(source, temporary, modelImportDirectory(context), cancel, progress)
            cancel()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            pins.record(target, source.pin)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    fun uninstall(part: String) {
        val target = files.getValue(part).target
        Files.deleteIfExists(target.toPath())
        Files.deleteIfExists(pins.receipt(target).toPath())
        Files.deleteIfExists(File(root, ".${target.name}.download").toPath())
    }

    companion object {
        const val BACKEND = "qwen3"
        const val CODEC = "codec"
        const val TALKER = "talker"
    }
}
