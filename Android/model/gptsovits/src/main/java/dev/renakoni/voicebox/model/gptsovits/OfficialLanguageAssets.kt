package dev.renakoni.voicebox.model.gptsovits

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Converts the pinned upstream GRU arrays with the same gate order as prepare_language_assets.py. */
internal object OfficialLanguageAssets {
    fun install(root: File, stage: File, openAsset: (String) -> InputStream, cancel: () -> Unit) {
        val target = File(root, "RuntimeAssets/language_frontends")
        val output = File(stage, "language_frontends").apply { mkdirs() }
        val english = File(output, "english").apply { mkdir() }
        val graphs = File(english, "external").apply { mkdir() }
        for (name in listOf("names.tsv", "dictionary.tsv", "encoder.onnx", "decoder.onnx")) {
            openAsset("english-g2p/$name").use { input -> File(if (name.endsWith(".onnx")) graphs else english, name).outputStream().use(input::copyTo) }
        }
        for (name in listOf("pos-tagdict.json", "pos-classes.json", "pos-weights.json")) File(stage, name).copyTo(File(english, name))
        ZipFile(File(stage, "checkpoint20.npz")).use { npz ->
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
        val japanese = File(output, "japanese").apply { mkdir() }
        val required = mutableSetOf("sys.dic", "unk.dic", "char.bin", "matrix.bin", "COPYING")
        val buffer = ByteArray(128 * 1024)
        DataInputStream(GZIPInputStream(File(stage, "open_jtalk_dic_utf_8-1.11.tar.gz").inputStream())).use { tar ->
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
        target.parentFile!!.mkdirs()
        if (target.exists()) dev.renakoni.voicebox.model.gptsovits.checkpoint.CharacterVoiceEditor.replaceDirectory(output, target)
        else Files.move(output.toPath(), target.toPath())
    }

    fun missing(root: File): List<String> {
        val base = File(root, "RuntimeAssets/language_frontends")
        val graphs = if (File(base, "english/external").isDirectory) {
            listOf("encoder.onnx", "decoder.onnx", "encoder.weights", "decoder.weights").map { "english/external/$it" }
        } else listOf("english/encoder.onnx", "english/decoder.onnx")
        val required = listOf("japanese/sys.dic", "japanese/char.bin", "japanese/unk.dic", "japanese/matrix.bin",
            "english/dictionary.tsv", "english/names.tsv", "english/pos-weights.json", "english/pos-classes.json",
            "english/pos-tagdict.json") + graphs
        return required.filterNot { File(base, it).isFile }.map { "language_frontends/$it" }
    }

    fun ready(root: File) = missing(root).isEmpty()
}
