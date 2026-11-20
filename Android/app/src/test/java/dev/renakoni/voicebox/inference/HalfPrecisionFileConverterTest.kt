package dev.renakoni.voicebox.inference

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HalfPrecisionFileConverterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun interruptedConversionPreservesPreviousOutputAndRemovesTemporaryFile() {
        val source = folder.newFile("source.bin").apply { writeBytes(ByteArray(1024 * 1024 + 2)) }
        val output = folder.newFile("output.bin").apply { writeText("keep") }
        assertThrows(IllegalStateException::class.java) {
            HalfPrecisionFileConverter.convertFp16FileToFp32File(source, output) { done, _ ->
                if (done > 0) error("cancel")
            }
        }
        assertEquals("keep", output.readText())
        assertEquals(setOf("source.bin", "output.bin"), folder.root.list()!!.toSet())
    }

    @Test fun progressCountsInputBytesAndDoesNotChangeConvertedWeights() {
        val source = folder.newFile("weights.fp16")
        val bytes = ByteArray(1024 * 1024 + 2) { if (it % 2 == 0) 0 else 0x3c }
        source.writeBytes(bytes)
        val output = folder.newFile("weights.fp32")
        val progress = mutableListOf<Pair<Long, Long>>()
        HalfPrecisionFileConverter.convertFp16FileToFp32File(source, output) { done, total -> progress += done to total }
        assertEquals(listOf(0L, 1048576L, bytes.size.toLong()), progress.map { it.first })
        assertTrue(progress.all { it.second == source.length() })
        // Every input is FP16 1.0; its IEEE-754 FP32 bytes are 00 00 80 3f.
        val expected = ByteArray(bytes.size * 2) { when (it % 4) { 2 -> 0x80.toByte(); 3 -> 0x3f; else -> 0 } }
        assertArrayEquals(expected, output.readBytes())
    }
}
