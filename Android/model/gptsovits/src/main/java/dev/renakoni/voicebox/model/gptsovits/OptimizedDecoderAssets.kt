package dev.renakoni.voicebox.model.gptsovits

import org.json.JSONObject
import java.io.File

/** Both graphs must agree on the FP32, head-major, scaled-K cache contract. */
object OptimizedDecoderAssets {
    private val graphs = mapOf(
        "t2s_first_stage_decoder_fp32.onnx" to "mobile-prefill.onnx",
        "t2s_stage_decoder_fp32.onnx" to "mobile-decoder.onnx",
    )

    fun resolve(directory: File, sharedCacheAllowed: Boolean = false): Map<String, File> {
        val manifest = File(directory, "mobile-decoder.json")
        if (!manifest.exists()) return emptyMap()
        val data = JSONObject(manifest.readText())
        require(data.getInt("schema_version") == 1 && data.getString("format") == "genie_t2s_head_cache_fp32_v1") {
            "Unsupported optimized decoder format."
        }
        val resolved = graphs.mapValues { (source, derived) ->
            SplitVocoderAssets.verify(File(directory, source), data.getJSONObject("sources").getString(source))
            File(directory, derived).also {
                SplitVocoderAssets.verify(it, data.getJSONObject("graphs").getString(derived))
            }
        }
        if (sharedCacheAllowed && data.has("shared_cache")) {
            val shared = data.getJSONObject("shared_cache")
            require(shared.getString("format") == "genie_t2s_shared_cache_fp32_v1") {
                "Unsupported shared decoder format."
            }
            val file = File(directory, "mobile-decoder-shared.onnx")
            SplitVocoderAssets.verify(file, shared.getString("sha256"))
            return resolved + ("t2s_stage_decoder_fp32.onnx" to file)
        }
        return resolved
    }
}
