package dev.renakoni.voicebox.model.qwen3.checkpoint

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Locale

/** The bridge supplies the official tokenizer, architecture and per-tensor Q4_K_M policy. */
internal class QwenCheckpointConverter(private val asset: (String) -> InputStream) {
    private data class Tensor(val name: String, val dims: List<Int>, val type: Int, val sourceType: Int,
        val start: Long, val end: Long, val rows: Int, val rowBytes: Int) {
        val width get() = dims.first()
        val bytes get() = rows.toLong() * rowBytes
    }

    fun convert(config: JSONObject, weights: InputStream, target: File, cancel: () -> Unit, progress: (Int) -> Unit): String? {
        val layout = asset("checkpoint-layout.json").bufferedReader().use { JSONObject(it.readText()) }
        val kind = config.getString("tts_model_type")
        val speaker = readSpeaker(layout, config)
        val input = DataInputStream(weights.buffered())
        val tensors = readTensors(layout, input, kind)
        target.outputStream().use { file ->
            val required = writeHeader(file, layout, tensors, kind, speaker, config.getJSONObject("talker_config"))
            require(target.usableSpace >= required) { "Insufficient storage for the Qwen Q4 weights" }
            writeWeights(file, input, tensors, cancel, progress)
            require(input.read() == -1) { "Trailing data in Qwen safetensors" }
            file.fd.sync()
        }
        return speaker?.lowercase(Locale.ROOT)
    }

    private fun readSpeaker(layout: JSONObject, config: JSONObject): String? {
        require(matches(layout.getJSONObject("config"), config)) { "Expected the standard Qwen3-TTS 0.6B 12Hz architecture" }
        val talker = config.getJSONObject("talker_config")
        val kind = config.getString("tts_model_type")
        val speakers = talker.optJSONObject("spk_id") ?: JSONObject()
        return when (kind) {
            "base" -> {
                require(speakers.length() == 0 && matches(layout.getJSONObject("speakerEncoder"),
                    config.getJSONObject("speaker_encoder_config"))) { "Invalid Base speaker encoder configuration" }
                null
            }
            "custom_voice" -> speakers.keys().asSequence().singleOrNull()?.also {
                require(it.isNotBlank() && it.length <= 256 && '\u0000' !in it &&
                    speakers.get(it) is Int && speakers.getInt(it) in 0 until talker.getInt("vocab_size")) { "Invalid Qwen speaker" }
            } ?: error("Expected a single-speaker CustomVoice checkpoint")
            else -> error("Unsupported Qwen checkpoint type: $kind")
        }
    }

    private fun readTensors(layout: JSONObject, input: DataInputStream, kind: String): List<Tensor> {
        val headerSize = java.lang.Long.reverseBytes(input.readLong())
        require(headerSize in 2..2_097_152L) { "Invalid safetensors header size" }
        val header = JSONObject(ByteArray(headerSize.toInt()).also(input::readFully).decodeToString())
        header.remove("__metadata__")
        val schema = layout.getJSONObject("tensors")
        val keys = schema.keys().asSequence().filter { kind == "base" || !it.startsWith("speaker_encoder.") }.toList()
        require(header.length() == keys.size) { "Checkpoint tensor inventory differs from Qwen 0.6B $kind" }
        val tensors = keys.map { key -> tensor(key, schema.getJSONObject(key), header.getJSONObject(key)) }.sortedBy { it.start }
        var offset = 0L
        for (tensor in tensors) {
            require(tensor.start == offset) { "Safetensors contains overlapping or missing data" }
            offset = tensor.end
        }
        return tensors
    }

    private fun tensor(key: String, spec: JSONObject, data: JSONObject): Tensor {
        val shape = data.getJSONArray("shape")
        val expected = spec.getJSONArray("shape")
        require(shape.length() == expected.length() && (0 until shape.length()).all { shape.get(it) == expected.get(it) }) {
            "Unexpected shape for $key"
        }
        val dims = spec.getJSONArray("dims").ints()
        val width = dims.first()
        val rows = dims.drop(1).fold(1, Math::multiplyExact)
        val type = spec.getInt("type")
        val rowBytes = when (type) {
            0 -> width * 4
            1 -> width * 2
            12 -> width / 256 * 144 // Q4_K
            14 -> width / 256 * 210 // Q6_K
            else -> error("Invalid Q4 template type")
        }
        val dtype = data.getString("dtype")
        val sourceType = when (dtype) {
            "F32" -> 0
            "F16" -> 1
            "BF16" -> 30
            else -> error("Unsupported safetensors dtype $dtype for $key")
        }
        val range = data.getJSONArray("data_offsets")
        require(range.length() == 2) { "Invalid tensor range for $key" }
        val start = range.integerOffset(0)
        val end = range.integerOffset(1)
        val size = width.toLong() * rows * if (sourceType == 0) 4 else 2
        require(start >= 0 && end >= start && end - start == size) { "Invalid tensor range for $key" }
        return Tensor(spec.getString("name"), dims, type, sourceType, start, end, rows, rowBytes)
    }

    private fun writeHeader(file: FileOutputStream, layout: JSONObject, tensors: List<Tensor>, kind: String,
        speaker: String?, talker: JSONObject): Long {
        val out = file.buffered()
        out.write("GGUF".toByteArray())
        out.le32(3)
        out.le64(tensors.size.toLong())
        out.le64(layout.getLong("fields") + if (speaker == null) 2 else 5)
        asset("checkpoint-metadata.bin").use { it.copyTo(out) }
        out.stringField("general.name", "Qwen3-TTS-12Hz-0.6B-$kind")
        out.stringField("qwen3-tts.model_type", kind)
        if (speaker != null) {
            out.arrayField("speaker_names", 8); out.ggufString(speaker.lowercase(Locale.ROOT))
            out.arrayField("speaker_ids", 4); out.le32(talker.getJSONObject("spk_id").getInt(speaker))
            val dialect = talker.optJSONObject("spk_is_dialect")?.opt(speaker) as? String ?: ""
            out.arrayField("speaker_dialects", 8); out.ggufString(dialect)
        }
        var offset = 0L
        for (tensor in tensors) {
            out.ggufString(tensor.name)
            out.le32(tensor.dims.size)
            tensor.dims.forEach { out.le64(it.toLong()) }
            out.le32(tensor.type)
            out.le64(offset)
            offset += tensor.bytes + padding(tensor.bytes)
        }
        out.flush()
        file.write(ByteArray(padding(file.channel.position())))
        return offset
    }

    private fun writeWeights(file: FileOutputStream, input: DataInputStream, tensors: List<Tensor>,
        cancel: () -> Unit, progress: (Int) -> Unit) {
        val totalInput = tensors.last().end
        val source = Channels.newChannel(input)
        val raw = ByteBuffer.allocateDirect(CHUNK)
        val packed = ByteBuffer.allocateDirect(CHUNK)
        val floats = ByteBuffer.allocateDirect(CHUNK)
        var percent = -1
        for (tensor in tensors) {
            val sourceBytes = if (tensor.sourceType == 0) 4 else 2
            val maxRows = CHUNK / (tensor.width * 4)
            var row = 0
            while (row < tensor.rows) {
                cancel()
                val count = minOf(maxRows, tensor.rows - row)
                raw.clear().limit(count * tensor.width * sourceBytes)
                while (raw.hasRemaining()) require(source.read(raw) >= 0) { "Truncated Qwen safetensors" }
                convertRows(raw, floats, packed, tensor.sourceType, tensor.type, tensor.width, count)
                packed.clear().limit(count * tensor.rowBytes)
                while (packed.hasRemaining()) file.channel.write(packed)
                row += count
                val current = ((tensor.start + row.toLong() * tensor.width * sourceBytes) * 100 / totalInput).toInt()
                if (current != percent) { percent = current; progress(percent) }
            }
            file.write(ByteArray(padding(tensor.bytes)))
        }
    }

    private external fun convertRows(input: ByteBuffer, floats: ByteBuffer, output: ByteBuffer, sourceType: Int,
        targetType: Int, width: Int, rows: Int)

    private companion object { const val CHUNK = 1024 * 1024 }
}

private fun JSONArray.ints() = List(length()) { getInt(it) }
private fun JSONArray.integerOffset(index: Int): Long = when (val value = get(index)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("Safetensors offsets must be integers")
}
private fun matches(expected: JSONObject, actual: JSONObject): Boolean = expected.keys().asSequence().all { key ->
    val value = expected.get(key)
    val other = actual.opt(key)
    when (value) {
        is JSONObject -> other is JSONObject && matches(value, other)
        is JSONArray -> other is JSONArray && value.toString() == other.toString()
        is Number -> other is Number && value.toDouble() == other.toDouble()
        else -> value == other
    }
}

private fun padding(size: Long) = ((32 - size % 32) % 32).toInt()
private fun OutputStream.le32(value: Int) { repeat(4) { write(value ushr (it * 8)) } }
private fun OutputStream.le64(value: Long) { repeat(8) { write((value ushr (it * 8)).toInt()) } }
private fun OutputStream.ggufString(value: String) {
    val bytes = value.toByteArray()
    le64(bytes.size.toLong())
    write(bytes)
}
private fun OutputStream.stringField(key: String, value: String) { ggufString(key); le32(8); ggufString(value) }
private fun OutputStream.arrayField(key: String, elementType: Int) {
    ggufString("qwen3-tts.codec.$key"); le32(9); le32(elementType); le64(1)
}
