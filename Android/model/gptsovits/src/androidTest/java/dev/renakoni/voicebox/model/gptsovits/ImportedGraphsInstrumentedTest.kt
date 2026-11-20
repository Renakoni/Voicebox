package dev.renakoni.voicebox.model.gptsovits

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class ImportedGraphsInstrumentedTest {
    @Test fun optimizedGraphsExposeInputsAndOutputs() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("importedModelDirectory")
        assumeNotNull(path)
        val modelDirectory = File(requireNotNull(path))
        val variant = args.getString("variant")
        assertTrue(OptimizedDecoderAssets.resolve(modelDirectory, true).values.any { it.name == "mobile-decoder-shared.onnx" })
        val graphs = if (variant == "v5turbo") {
            listOf("t2s_encoder_fp32.onnx", "mobile-prefill.onnx", "mobile-decoder-shared.onnx",
                "v5-reference-audio.onnx", "v5-condition.onnx", "v5-static.onnx", "v5-dit.onnx")
        } else {
            SplitVocoderAssets.validate(modelDirectory)
            listOf("t2s_encoder_fp32.onnx", "mobile-prefill.onnx", "mobile-decoder-shared.onnx",
                "prompt_encoder_fp32.onnx", "split-encoder.onnx", "split-hifigan.onnx")
        }
        for (graph in graphs) {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                OrtEnvironment.getEnvironment().createSession(File(modelDirectory, graph).absolutePath, options).use { session ->
                    assertTrue(session.inputNames.isNotEmpty()); assertTrue(session.outputNames.isNotEmpty())
                }
            }
        }
    }
}
