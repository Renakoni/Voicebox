package dev.renakoni.voicebox.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WavFileWriterTest {

    @Test
    fun convertsSamplesToLittleEndianPcm16Buffer() {
        val bytes = WavFileWriter.toPcm16LeBytes(floatArrayOf(-2f, -1f, 0f, 1f, 2f))

        assertEquals(
            listOf(
                0x01, 0x80,
                0x01, 0x80,
                0x00, 0x00,
                0xff, 0x7f,
                0xff, 0x7f,
            ),
            bytes.map { it.toInt() and 0xff },
        )
    }
}
