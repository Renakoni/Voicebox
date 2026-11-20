package dev.renakoni.voicebox.model.gptsovits.checkpoint

import dev.renakoni.voicebox.model.gptsovits.HalfPrecision
import dev.renakoni.voicebox.core.i18n.LocalizedFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TorchCheckpointTest {
    @Test fun refusesExecutablePickleGlobals() {
        val pickle = byteArrayOf(0x80.toByte(), 2) + "cos\nsystem\n(S'echo unsafe'\ntR.".toByteArray()
        val error = assertThrows(IllegalArgumentException::class.java) { TorchPickle(pickle).read() }
        assertEquals("checkpoint_object_unsupported", (error as LocalizedFailure).text.key)
    }

    @Test fun rejectsTruncatedMetadataAndOutOfBoundsTensor() {
        assertThrows(IllegalArgumentException::class.java) { TorchPickle(byteArrayOf(0x80.toByte(), 2, 'X'.code.toByte(), 1)).read() }
        assertThrows(IllegalArgumentException::class.java) {
            TorchTensor(TorchStorage("HalfStorage", "0", 4), 2, listOf(4), listOf(1)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorchTensor(TorchStorage("HalfStorage", "0", 8), 0, listOf(4), listOf(2)).validate()
        }
    }

    @Test fun binary16ConversionPreservesEveryFiniteHalfAndRoundsTiesToEven() {
        for (bits in 0..65535) {
            if (bits and 0x7c00 == 0x7c00) continue
            assertEquals(bits, CheckpointImporter.floatToHalf(HalfPrecision.fp16BitsToFloat(bits)))
        }
        assertEquals(0x3c00, CheckpointImporter.floatToHalf(1f + 1f / 2048))
        assertEquals(0x3c02, CheckpointImporter.floatToHalf(1f + 3f / 2048))
        assertEquals(0, CheckpointImporter.floatToHalf(Math.scalb(1f, -25)))
        assertEquals(2, CheckpointImporter.floatToHalf(3 * Math.scalb(1f, -25)))
    }

    @Test fun readsRealTorchExportsAndRejectsNonContiguousTensor() {
        for (protocol in listOf(2, 4)) {
            val file = File("src/test/resources/checkpoint/torch-protocol-$protocol.ptfixture")
            TorchCheckpoint(file).use { checkpoint ->
                val config = checkpoint.root["config"] as Map<*, *>
                assertEquals(192L, (config["model"] as Map<*, *>)["hidden_channels"])
                assertEquals(listOf(2L, 3L), checkpoint.tensor("half").shape)
                assertEquals("FloatStorage", checkpoint.tensor("float").storage.type)
                assertEquals("BFloat16Storage", checkpoint.tensor("bfloat").storage.type)
                val slice = checkpoint.tensor("slice")
                assertEquals(2L, slice.offset)
                val bytes = checkpoint.open(slice).use { it.readBytes() }
                assertEquals(0x40, bytes[1].toInt()) // FP16 2.0 at storage offset 2.
                assertThrows(IllegalArgumentException::class.java) { checkpoint.tensor("transpose") }
            }
        }
    }

    @Test fun cancellationAndBadInputLeaveExistingModelsUntouched() {
        val root = Files.createTempDirectory("checkpoint-rollback-").toFile()
        try {
            val sentinel = File(root, "CharacterModels/v2ProPlus/kept/tts_models/keep.txt")
            requireNotNull(sentinel.parentFile).mkdirs(); sentinel.writeText("keep")
            val assets = File("src/main/assets")
            val importer = CheckpointImporter(root) { File(assets, it).inputStream() }
            assertThrows(IllegalStateException::class.java) {
                importer.import("cancelled", { byteArrayOf().inputStream() }, { byteArrayOf().inputStream() },
                    checkCancelled = { error("cancel") })
            }
            assertThrows(IllegalArgumentException::class.java) {
                importer.import("bad", { "invalid".byteInputStream() }, { "invalid".byteInputStream() })
            }
            assertEquals("keep", sentinel.readText())
            assertEquals(listOf("CharacterModels"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }
}
