package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.fileStamp
import dev.renakoni.voicebox.core.regularFileAttributes
import java.io.File
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object HalfPrecisionFileConverter {
    fun prepare(filesRoot: File, sourceFile: File, outputFile: File, progress: (Long, Long) -> Unit) {
        val sourceStamp = requireNotNull(sourceFile.regularFileAttributes()) { "Missing FP16 file: ${sourceFile.name}" }
        val total = sourceStamp.size()
        require(total > 0 && total % 2L == 0L) { "Invalid FP16 file length: ${sourceFile.name}" }
        val receipt = File(filesRoot, ".gpt-packages/derived/${outputFile.relativeTo(filesRoot).path}.stamp")
        val current = outputFile.regularFileAttributes()
        if (current != null && current.size() == total * 2 && receipt.isFile &&
            receipt.readText() == "${sourceStamp.fileStamp()}\n${current.fileStamp()}") return
        var consumed = 0L
        progress(0, total)
        // The service serializes conversion. Reuse the scratch path after a process interruption.
        val temporary = File(outputFile.parentFile, ".${outputFile.name}.tmp")
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
            check(sourceFile.regularFileAttributes()?.fileStamp() == sourceStamp.fileStamp()) { "FP16 source changed during conversion" }
            Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            receipt.parentFile!!.mkdirs()
            receipt.writeText("${sourceStamp.fileStamp()}\n${requireNotNull(outputFile.regularFileAttributes()).fileStamp()}")
        } finally {
            temporary.delete()
        }
    }
}
