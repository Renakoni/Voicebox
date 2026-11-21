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
        val shared = if (sharedCacheAllowed) data.optJSONObject("shared_cache") else null
        if (shared != null) require(shared.getString("format") == "genie_t2s_shared_cache_fp32_v1") {
            "Unsupported shared decoder format."
        }
        return graphs.mapValues { (source, derived) ->
            SplitVocoderAssets.verify(File(directory, source), data.getJSONObject("sources").getString(source))
            if (derived == "mobile-decoder.onnx" && shared != null) {
                File(directory, "mobile-decoder-shared.onnx").also { SplitVocoderAssets.verify(it, shared.getString("sha256")) }
            } else {
                File(directory, derived).also { SplitVocoderAssets.verify(it, data.getJSONObject("graphs").getString(derived)) }
            }
        }
    }
}
