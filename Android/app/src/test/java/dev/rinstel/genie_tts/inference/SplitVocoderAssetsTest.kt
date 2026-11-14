package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.io.path.createTempDirectory

class SplitVocoderAssetsTest {
    @Test
    fun changedDerivedGraphIsRejectedBeforeSessionCreation() {
        val directory = createTempDirectory().toFile()
        try {
            val graph = directory.resolve("split-hifigan.onnx").apply { writeText("abc") }
            val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            SplitVocoderAssets.verify(graph, hash)
            graph.appendText("changed")
            assertThrows(IllegalArgumentException::class.java) { SplitVocoderAssets.verify(graph, hash) }
        } finally { directory.deleteRecursively() }
    }
}
