package dev.renakoni.voicebox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.renakoni.voicebox.core.WavFileWriter
import dev.renakoni.voicebox.qqvoice.QqSilk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class QqVoiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun codecPreservesPartialFrameAndOriginalWav() {
        val dir = File(context.cacheDir, "qq-codec-test").apply { mkdirs() }
        val source = File(dir, "source.wav")
        WavFileWriter.writeMonoPcm16(source, FloatArray(1003) { (0.4 * sin(it * 2 * PI * 440 / 32000)).toFloat() }, 32000)
        val original = source.readBytes()
        val result = QqSilk.fromWav(source)
        assertArrayEquals(original, source.readBytes())
        assertEquals(31, result.durationMs)
        assertEquals(30, result.waveform.size)
        assertTrue(result.waveform.any { it > 0 })
        assertArrayEquals(byteArrayOf(2) + "#!SILK_V3".toByteArray(), result.bytes.take(10).toByteArray())
        assertEquals(2, packetCount(result.bytes))
        source.delete(); dir.delete()
    }

    @Test fun fractionalSecondsArePreservedInTheEncodedAudio() {
        val source = File(context.cacheDir, "qq-10-point-2-seconds.wav")
        try {
            WavFileWriter.writeMonoPcm16(source, FloatArray(326400) { (0.2 * sin(it * 2 * PI * 440 / 32000)).toFloat() }, 32000)
            val original = source.readBytes()
            val result = QqSilk.fromWav(source)
            assertEquals(10200, result.durationMs)
            assertEquals(510, packetCount(result.bytes))
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete() }
    }

    private fun packetCount(bytes: ByteArray): Int {
        var offset = 10
        var packets = 0
        while (offset < bytes.size) {
            assertTrue(offset + 2 <= bytes.size)
            val length = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            assertTrue(length > 0 && offset + 2 + length <= bytes.size)
            offset += 2 + length
            packets++
        }
        assertEquals(bytes.size, offset)
        return packets
    }
}
