package dev.rinstel.genie_tts.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OptimizedDecoderAssetsInstrumentedTest {
    @Test
    fun requiresMatchingSourceAndBothDerivedGraphs() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "decoder-contract-test")
        directory.mkdirs()
        try {
            assertEquals(emptyMap<String, File>(), OptimizedDecoderAssets.resolve(directory))
            val source = listOf("t2s_first_stage_decoder_fp32.onnx", "t2s_stage_decoder_fp32.onnx")
            val derived = listOf("mobile-prefill.onnx", "mobile-decoder.onnx")
            fun hashes(names: List<String>): JSONObject = JSONObject().apply {
                names.forEach { name ->
                    File(directory, name).writeText(name)
                    put(name, MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) })
                }
            }
            val manifest = JSONObject().put("schema_version", 1).put("format", "genie_t2s_head_cache_fp32_v1")
                .put("sources", hashes(source)).put("graphs", hashes(derived))
            File(directory, "mobile-decoder.json").writeText(manifest.toString())
            assertEquals(source.toSet(), OptimizedDecoderAssets.resolve(directory).keys)
            val shared = "mobile-decoder-shared.onnx"
            manifest.put("shared_cache", JSONObject().put("format", "genie_t2s_shared_cache_fp32_v1")
                .put("sha256", hashes(listOf(shared)).getString(shared)))
            File(directory, "mobile-decoder.json").writeText(manifest.toString())
            assertEquals(derived[1], OptimizedDecoderAssets.resolve(directory)[source[1]]?.name)
            assertEquals(shared, OptimizedDecoderAssets.resolve(directory, sharedCacheAllowed = true)[source[1]]?.name)
            File(directory, shared).appendText("changed")
            assertThrows(IllegalArgumentException::class.java) {
                OptimizedDecoderAssets.resolve(directory, sharedCacheAllowed = true)
            }
            assertEquals(derived[1], OptimizedDecoderAssets.resolve(directory)[source[1]]?.name)
            File(directory, shared).writeText(shared)
            File(directory, source[0]).appendText("changed")
            assertThrows(IllegalArgumentException::class.java) { OptimizedDecoderAssets.resolve(directory) }
            File(directory, source[0]).writeText(source[0])
            File(directory, derived[1]).appendText("changed")
            assertThrows(IllegalArgumentException::class.java) { OptimizedDecoderAssets.resolve(directory) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
