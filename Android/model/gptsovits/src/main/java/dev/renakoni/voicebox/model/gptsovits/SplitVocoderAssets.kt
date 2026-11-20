package dev.renakoni.voicebox.model.gptsovits

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Bind optional derived graphs to the unchanged source graph before GPU use. */
object SplitVocoderAssets {
    const val ENCODER = "split-encoder.onnx"
    const val HIFIGAN = "split-hifigan.onnx"
    const val BOUNDARY = "/vq_model/Mul_2_output_0"

    fun validate(directory: File): List<String> {
        val manifest = File(directory, "split-vits.json")
        require(manifest.isFile) { "This voice needs the V2ProPlus CPU + GPU package (split-vits.json). CPU remains available." }
        val data = JSONObject(manifest.readText())
        require(data.getInt("schema_version") == 1 && data.getString("format") == "genie_v2proplus_split_v1" &&
            data.getString("boundary") == BOUNDARY) { "Unsupported split vocoder format." }
        verify(File(directory, "vits_fp32.onnx"), data.getString("source_sha256"))
        val graphs = data.getJSONObject("graphs")
        return listOf(ENCODER, HIFIGAN).onEach { verify(File(directory, it), graphs.getString(it)) }
    }

    internal fun verify(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            var count = stream.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected) { "Model graph hash mismatch: ${file.name}. Reimport the matching voice package." }
    }
}
