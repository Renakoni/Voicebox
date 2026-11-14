package dev.rinstel.genie_tts.inference

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HalfPrecisionFileConverter {
    fun convertFp16FileToFp32File(sourceFile: File, outputFile: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        outputFile.parentFile?.mkdirs()
        val total = sourceFile.length()
        var consumed = 0L
        progress(0, total)
        val outputBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        sourceFile.inputStream().buffered().use { input ->
            outputFile.outputStream().buffered().use { output ->
                while (true) {
                    val low = input.read()
                    if (low == -1) {
                        break
                    }
                    val high = input.read()
                    require(high != -1) { "Invalid FP16 file length: ${sourceFile.name}" }

                    val bits = low or (high shl 8)
                    outputBuffer.clear()
                    outputBuffer.putFloat(HalfPrecision.fp16BitsToFloat(bits))
                    output.write(outputBuffer.array())
                    consumed += 2
                    if (consumed % (1024 * 1024) == 0L) progress(consumed, total)
                }
            }
        }
        progress(consumed, total)
    }
}
