package dev.rinstel.genie_tts.inference

import java.io.File
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object HalfPrecisionFileConverter {
    fun convertFp16FileToFp32File(sourceFile: File, outputFile: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        outputFile.parentFile?.mkdirs()
        val total = sourceFile.length()
        require(sourceFile.isFile && total > 0 && total % 2L == 0L) { "Invalid FP16 file length: ${sourceFile.name}" }
        var consumed = 0L
        progress(0, total)
        val temporary = Files.createTempFile(outputFile.toPath().toAbsolutePath().parent, ".${outputFile.name}-", ".tmp").toFile()
        try {
            val bytes = ByteArray(64 * 1024)
            val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val converted = ByteBuffer.allocate(bytes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            DataInputStream(sourceFile.inputStream()).use { input ->
                temporary.outputStream().use { output ->
                    while (consumed < total) {
                        val count = minOf(bytes.size.toLong(), total - consumed).toInt()
                        input.readFully(bytes, 0, count)
                        source.clear()
                        converted.clear()
                        repeat(count / 2) { converted.putFloat(HalfPrecision.fp16BitsToFloat(source.short.toInt() and 65535)) }
                        output.write(converted.array(), 0, count * 2)
                        consumed += count
                        if (consumed % (1024 * 1024) == 0L) progress(consumed, total)
                    }
                }
            }
            progress(consumed, total)
            Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
