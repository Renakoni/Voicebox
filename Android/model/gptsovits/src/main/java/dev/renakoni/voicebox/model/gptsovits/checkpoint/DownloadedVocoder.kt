package dev.renakoni.voicebox.model.gptsovits.checkpoint

import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Converts the official flat V5 vocoder checkpoint into the existing mobile runtime layout. */
class DownloadedVocoder(private val root: File, private val openAsset: (String) -> InputStream) {
    fun install(source: File, checkCancelled: () -> Unit) {
        val target = File(root, "RuntimeAssets/v5-vocoder")
        val work = File(root, ".gpt-packages/vocoder")
        val stage = File(work, "output").apply { mkdirs() }
        try {
            val manifest = openAsset("official-runtime/v5-vocoder.json").use { JSONObject(it.bufferedReader().readText()) }
            val digest = MessageDigest.getInstance("SHA-256")
            val writer = TensorWriter()
            TorchCheckpoint(source, weightsKey = null).use { checkpoint ->
                DigestOutputStream(File(stage, "v5_vocoder_fp32.bin").outputStream().buffered(), digest).use { output ->
                    val tensors = manifest.getJSONArray("tensors")
                    for (index in 0 until tensors.length()) {
                        checkCancelled()
                        val entry = tensors.getJSONObject(index)
                        val tensor = checkpoint.tensor(entry.getString("key"))
                        val expected = entry.getJSONArray("shape").let { shape -> List(shape.length()) { shape.getLong(it) } }
                        requireUi(tensor.shape == expected) { UiText("vocoder_shape_mismatch") }
                        checkpoint.open(tensor).use { writer.write(it, output, tensor, false, checkCancelled) }
                    }
                }
            }
            requireUi(CheckpointImporter.hex(digest.digest()) == manifest.getString("weights_sha256")) { UiText("vocoder_checksum_failed") }
            openAsset("official-runtime/v5-vocoder-subpixel.onnx").use { input ->
                File(stage, "v5-vocoder-subpixel.onnx").outputStream().use(input::copyTo)
            }
            checkCancelled()
            target.parentFile!!.mkdirs()
            if (target.isDirectory) CharacterVoiceEditor.replaceDirectory(stage, target)
            else Files.move(stage.toPath(), target.toPath())
        } finally { stage.deleteRecursively() }
    }
}
