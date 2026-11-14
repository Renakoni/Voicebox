package dev.rinstel.genie_tts.checkpoint

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Edits a voice while the service owns and has closed its model sessions. */
class CharacterVoiceEditor(private val filesRoot: File) {
    fun character(id: String, version: String = "v2ProPlus"): File {
        require(version in setOf("v2", "v2ProPlus")) { "不支持此音色版本。" }
        val root = File(filesRoot, "CharacterModels/$version").canonicalFile
        val target = File(root, id).canonicalFile
        require(target.parentFile == root && id == target.name && !id.startsWith('.')) { "无效的音色目录。" }
        require(File(target, "tts_models").isDirectory) { "音色模型不存在，请重新导入。" }
        return target
    }

    fun updateReference(id: String, name: String, reference: CheckpointImporter.Reference, version: String = "v2ProPlus") {
        require(name.isNotBlank() && name.length <= 80) { "请填写 1 至 80 字的音色名称。" }
        require(reference.text.isNotBlank() && reference.audioFile.isFile) { "请选择参考音频并填写原文。" }
        val target = character(id, version)
        val manifest = File(target, "prompt_wav.json")
        val json = JSONObject(manifest.readText())
        val previous = json.getJSONObject("Normal").getString("wav")
        val audioRoot = File(target, "prompt_wav")
        val oldAudio = File(audioRoot, previous).canonicalFile
        val replacingAudio = reference.audioFile.canonicalFile != oldAudio
        val audio = if (replacingAudio) File(audioRoot, "reference-${UUID.randomUUID()}.${reference.audioFile.extension}") else oldAudio
        val temporary = File(target, ".prompt-${UUID.randomUUID()}.json")
        var saved = false
        try {
            if (replacingAudio) reference.audioFile.copyTo(audio)
            json.put("display_name", name.trim()).put("Normal", JSONObject().put("wav", audio.name)
                .put("text", reference.text.trim()).put("language", reference.language))
            temporary.writeText(json.toString(2))
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            saved = true
            val stillUsed = json.keys().asSequence().filter { it != "Normal" }
                .any { json.optJSONObject(it)?.optString("wav") == previous }
            if (replacingAudio && !stillUsed && oldAudio.parentFile == audioRoot.canonicalFile) oldAudio.delete()
        } finally {
            temporary.delete()
            if (!saved && replacingAudio) audio.delete()
        }
    }

    companion object {
        /** Publish a fully converted replacement under the same model ID. No old weights remain after success. */
        fun replaceDirectory(prepared: File, installed: File) {
            require(prepared.isDirectory && installed.isDirectory)
            val previous = File(prepared.parentFile, ".previous")
            Files.move(installed.toPath(), previous.toPath())
            try {
                Files.move(prepared.toPath(), installed.toPath())
            } catch (failure: Exception) {
                // If restoration itself fails, keep the previous directory for recovery.
                try { Files.move(previous.toPath(), installed.toPath()) }
                catch (restore: Exception) { failure.addSuppressed(restore) }
                throw failure
            }
            previous.deleteRecursively()
        }
    }
}
