package dev.renakoni.voicebox.model.gptsovits

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Converts the pinned upstream GRU arrays with the same gate order as prepare_language_assets.py. */
internal object OfficialLanguageAssets {
    fun buildEnglish(checkpoint: File, graphs: File, openAsset: (String) -> InputStream, cancel: () -> Unit) {
        for (name in listOf("encoder.onnx", "decoder.onnx")) {
            openAsset("english-g2p/$name").use { input -> File(graphs, name).outputStream().use(input::copyTo) }
        }
        ZipFile(checkpoint).use { npz ->
            fun array(name: String, elements: Int): ByteArray {
                cancel()
                val entry = requireNotNull(npz.getEntry("$name.npy"))
                return DataInputStream(npz.getInputStream(entry)).use { input ->
                    val magic = ByteArray(8).also(input::readFully)
                    require(magic.contentEquals(byteArrayOf(0x93.toByte(), 78, 85, 77, 80, 89, 1, 0)))
                    val headerBytes = input.readUnsignedByte() + (input.readUnsignedByte() shl 8)
                    val header = ByteArray(headerBytes).also(input::readFully).decodeToString()
                    require("'<f4'" in header && "'fortran_order': False" in header && entry.size == 10L + headerBytes + elements * 4L)
                    ByteArray(elements * 4).also(input::readFully)
                }
            }
            for ((prefix, stem) in listOf("enc" to "encoder", "dec" to "decoder")) {
                File(graphs, "$stem.weights").outputStream().buffered().use { weights ->
                    weights.write(array("${prefix}_emb", (if (prefix == "enc") 29 else 74) * 256))
                    fun gates(name: String, count: Int) {
                        val values = array("${prefix}_$name", count)
                        val size = values.size / 3
                        for (gate in listOf(1, 0, 2)) weights.write(values, gate * size, size)
                    }
                    gates("w_ih", 768 * 256); gates("w_hh", 768 * 256); gates("b_ih", 768); gates("b_hh", 768)
                    if (prefix == "dec") {
                        val fc = array("fc_w", 74 * 256)
                        for (i in 0 until 256) for (j in 0 until 74) weights.write(fc, 4 * (j * 256 + i), 4)
                        weights.write(array("fc_b", 74))
                    }
                }
            }
        }
    }

    fun buildJapanese(archive: File, japanese: File, cancel: () -> Unit) {
        val required = mutableSetOf("sys.dic", "unk.dic", "char.bin", "matrix.bin", "COPYING")
        val buffer = ByteArray(128 * 1024)
        DataInputStream(GZIPInputStream(archive.inputStream())).use { tar ->
            val header = ByteArray(512)
            fun copy(count: Long, to: OutputStream?) {
                var remaining = count
                while (remaining > 0) {
                    cancel()
                    val length = minOf(remaining, buffer.size.toLong()).toInt()
                    tar.readFully(buffer, 0, length)
                    to?.write(buffer, 0, length)
                    remaining -= length
                }
            }
            while (true) {
                tar.readFully(header)
                if (header.all { it == 0.toByte() }) break
                val name = header.decodeToString(0, 100).substringBefore('\u0000').substringAfterLast('/')
                val size = header.decodeToString(124, 136).trim('\u0000', ' ').toLong(8)
                require(size in 0..150_000_000)
                if (required.remove(name)) File(japanese, name).outputStream().use { copy(size, it) } else copy(size, null)
                copy((512 - size % 512) % 512, null)
            }
        }
        require(required.isEmpty())
        cancel()

    }

    fun requiredFiles(externalEnglish: Boolean): List<String> {
        val graphs = if (externalEnglish) {
            listOf("encoder.onnx", "decoder.onnx", "encoder.weights", "decoder.weights").map { "english/external/$it" }
        } else listOf("english/encoder.onnx", "english/decoder.onnx")
        val required = listOf("japanese/sys.dic", "japanese/char.bin", "japanese/unk.dic", "japanese/matrix.bin",
            "english/dictionary.tsv", "english/names.tsv", "english/pos-weights.json", "english/pos-classes.json",
            "english/pos-tagdict.json") + graphs
        return required.map { "language_frontends/$it" }
    }

}
