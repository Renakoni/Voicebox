package dev.renakoni.voicebox.model.qwen3.checkpoint

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.core.deleteFileTree
import dev.renakoni.voicebox.core.openBackendAsset
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class QwenCheckpointVoices(private val app: Context, private val bridge: File) {
    private val root = File(app.noBackupFilesDir, "qwen3-voices")
    private val stage get() = File(root, ".import")

    data class Voice(val model: CharacterModel, val weights: File, val speaker: String?)
    @Volatile private var voices = emptyMap<ModelRef, Voice>()

    private fun model(directory: File, name: String) = CharacterModel(
        ModelRef("qwen3", "Q4_K_M", directory.name), name, "${root.name}/${directory.name}")

    private fun read(directory: File): Voice {
        val metadata = JSONObject(File(directory, "voice.json").readText())
        return Voice(model(directory, metadata.getString("name")), File(directory, "talker.gguf"),
            metadata.optString("speaker").ifEmpty { null })
    }

    fun discover(): List<Voice> {
        val found = root.listFiles().orEmpty().filter { !it.name.startsWith('.') && File(it, "talker.gguf").isFile }
            .sortedBy { it.name }.mapNotNull { dir ->
                try {
                    read(dir)
                } catch (e: Exception) {
                    if (e !is java.io.IOException && e !is org.json.JSONException) throw e
                    Log.w("QwenVoices", "Cannot read voice metadata in ${dir.name}", e)
                    null
                }
            }
        voices = found.associateBy { it.model.ref }
        return found
    }

    fun find(model: CharacterModel): Voice =
        voices[model.ref] ?: discover().single { it.model.ref == model.ref }

    fun recoverInterrupted() = deleteFileTree(stage)

    fun import(name: String, config: Uri, weights: Uri, cancel: () -> Unit, progress: (Int) -> Unit): CharacterModel {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 256) { "Invalid Qwen voice name" }
        fun open(uri: Uri): InputStream = requireNotNull(app.contentResolver.openInputStream(uri)) { "Cannot open $uri" }
        val configuration = open(config).use { input ->
            val bytes = ByteArray(256 * 1024)
            var used = 0
            while (used < bytes.size) {
                val n = input.read(bytes, used, bytes.size - used)
                if (n < 0) break
                used += n
            }
            require(input.read() == -1) { "Qwen config.json is too large" }
            JSONObject(bytes.decodeToString(0, used))
        }
        check(root.mkdirs() || root.isDirectory)
        // Library actions run on the single import worker. A process death can leave only the hidden stage.
        recoverInterrupted()
        check(stage.mkdir())
        try {
            cancel()
            System.load(File(bridge, "cpu/libqwen3_jni.so").absolutePath)
            System.load(File(bridge, "libqwen3_checkpoint.so").absolutePath)
            val speaker = open(weights).use {
                QwenCheckpointConverter(app::openBackendAsset).convert(configuration, it, File(stage, "talker.gguf"), cancel, progress)
            }
            File(stage, "voice.json").writeText(JSONObject().put("name", label).put("speaker", speaker).toString())
            val destination = File(root, UUID.randomUUID().toString())
            cancel()
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return model(destination, label)
        } catch (error: Throwable) {
            recoverInterrupted()
            throw error
        }
    }
}
