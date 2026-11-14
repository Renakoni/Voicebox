package dev.rinstel.genie_tts.inference

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class ReferenceAudioFeatures(
    val sslContent: FloatTensorData,
    val speakerEmbedding: FloatTensorData?,
)

/** Two computed audio contexts, preserved across process restarts. No model sessions or generated speech. */
internal class ReferenceAudioFeatureCache(private val directory: File) {
    fun load(key: String): ReferenceAudioFeatures? {
        val file = File(directory, "$key.bin")
        return try {
            if (!file.isFile || file.length() !in 33..MAX_BYTES.toLong()) return null
            val bytes = file.readBytes()
            if (bytes.size !in 33..MAX_BYTES) return null
            val payloadSize = bytes.size - 32
            val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, payloadSize) }.digest()
            if (!digest.contentEquals(bytes.copyOfRange(payloadSize, bytes.size))) return null
            DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
                if (input.readInt() != FORMAT) return null
                val ssl = input.readTensor()
                val speaker = if (input.readBoolean()) input.readTensor() else null
                if (input.available() != 0) return null
                file.setLastModified(System.currentTimeMillis())
                ReferenceAudioFeatures(ssl, speaker)
            }
        } catch (_: IOException) {
            null // Eviction, interrupted writes and unavailable storage are cache misses.
        }
    }

    fun store(key: String, features: ReferenceAudioFeatures): Boolean {
        val elements = features.sslContent.values.size.toLong() + (features.speakerEmbedding?.values?.size ?: 0)
        if (elements > (MAX_BYTES - 256) / 4) return false
        // Generation is serialized by the service. A fixed staging file also
        // bounds leftovers if Android kills the process during a write.
        val temporary = File(directory, ".reference-audio.tmp")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(FORMAT)
                    output.writeTensor(features.sslContent)
                    output.writeBoolean(features.speakerEmbedding != null)
                    features.speakerEmbedding?.let { output.writeTensor(it) }
                }
            }.toByteArray()
            if (bytes.size > MAX_BYTES - 32) return false
            temporary.outputStream().buffered().use { output ->
                output.write(bytes)
                output.write(MessageDigest.getInstance("SHA-256").digest(bytes))
            }
            val target = File(directory, "$key.bin")
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            directory.listFiles().orEmpty().filter { it.name.matches(ENTRY_NAME) && it != target }
                .sortedByDescending(File::lastModified).drop(1).forEach(File::delete)
            true
        } catch (_: IOException) {
            false // Inference remains usable when the cache cannot be written.
        } finally {
            temporary.delete()
        }
    }

    private fun DataOutputStream.writeTensor(tensor: FloatTensorData) {
        writeInt(tensor.shape.size)
        tensor.shape.forEach(::writeLong)
        writeInt(tensor.values.size)
        tensor.values.forEach { writeInt(it.toRawBits()) }
    }

    private fun DataInputStream.readTensor(): FloatTensorData {
        val rank = readInt()
        if (rank !in 1..4) throw IOException("Invalid cached tensor rank")
        val shape = LongArray(rank) { readLong() }
        var count = 1L
        for (dimension in shape) {
            if (dimension <= 0 || dimension > MAX_BYTES / 4 || count > (MAX_BYTES / 4) / dimension) {
                throw IOException("Invalid cached tensor shape")
            }
            count *= dimension
        }
        if (readInt().toLong() != count || count * 4 > available()) throw IOException("Truncated cached tensor")
        return FloatTensorData(FloatArray(count.toInt()) { Float.fromBits(readInt()) }, shape)
    }

    companion object {
        private const val FORMAT = 0x47524101
        private const val MAX_BYTES = 32 * 1024 * 1024
        private val ENTRY_NAME = Regex("[0-9a-f]{64}\\.bin")

        fun key(audioHash: String, namespace: String, modelFiles: List<File>): String {
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeUTF(namespace)
                    output.writeUTF(audioHash)
                    for (file in modelFiles.sortedBy(File::getAbsolutePath)) {
                        output.writeUTF(file.canonicalPath)
                        output.writeLong(file.length())
                        output.writeLong(file.lastModified())
                    }
                }
            }.toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
