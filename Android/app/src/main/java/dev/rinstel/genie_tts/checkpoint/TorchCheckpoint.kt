package dev.rinstel.genie_tts.checkpoint

import dev.rinstel.genie_tts.R
import dev.rinstel.genie_tts.i18n.UiText
import dev.rinstel.genie_tts.i18n.requireUi
import dev.rinstel.genie_tts.i18n.errorUi

import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import net.lingala.zip4j.ZipFile

internal data class TorchStorage(val type: String, val key: String, val count: Long) {
    val bytesPerElement: Int get() = when (type) {
        "HalfStorage", "BFloat16Storage" -> 2
        "FloatStorage" -> 4
        else -> errorUi(UiText(R.string.checkpoint_precision_unsupported, type))
    }
}

internal data class TorchTensor(
    val storage: TorchStorage, val offset: Long, val shape: List<Long>, val stride: List<Long>,
) {
    val elements: Long get() = shape.fold(1L, Math::multiplyExact)

    fun validate() {
        requireUi(shape.size == stride.size && shape.size <= 8 && shape.all { it > 0 }) { UiText(R.string.tensor_shape_invalid) }
        var contiguous = 1L
        for (index in shape.indices.reversed()) {
            requireUi(shape[index] == 1L || stride[index] == contiguous) { UiText(R.string.tensor_noncontiguous) }
            contiguous = Math.multiplyExact(contiguous, shape[index])
        }
        requireUi(offset >= 0 && Math.addExact(offset, elements) <= storage.count) { UiText(R.string.tensor_out_of_bounds) }
    }
}

/** Reads tensor metadata only. Pickle globals are symbols, never executable Python objects. */
internal class TorchPickle(private val bytes: ByteArray) {
    private data class Symbol(val module: String, val name: String)
    private class Params : LinkedHashMap<Any?, Any?>()
    private fun isParams(symbol: Symbol) = symbol.name == "HParams" && symbol.module in setOf("utils", "GPT_SoVITS.utils")
    private val marker = Any()
    private var position = 0
    private val stack = arrayListOf<Any?>()
    private val memo = hashMapOf<Int, Any?>()
    private fun byte(): Int { requireUi(position < bytes.size) { UiText(R.string.metadata_truncated) }; return bytes[position++].toInt() and 255 }
    private fun unsigned(count: Int): Long {
        var value = 0L
        repeat(count) { value = value or (byte().toLong() shl (it * 8)) }
        return value
    }
    private fun text(length: Int): String {
        requireUi(length in 0..1_048_576 && length <= bytes.size - position) { UiText(R.string.metadata_length_invalid) }
        return bytes.copyOfRange(position, position + length).toString(Charsets.UTF_8).also { position += length }
    }
    private fun line(): String {
        val start = position
        while (position < bytes.size && bytes[position] != 10.toByte()) position++
        requireUi(position < bytes.size && position - start <= 4096) { UiText(R.string.metadata_format_invalid) }
        return bytes.copyOfRange(start, position++).toString(Charsets.UTF_8)
    }
    private fun pop(): Any? { requireUi(stack.isNotEmpty()) { UiText(R.string.metadata_stack_empty) }; return stack.removeAt(stack.lastIndex) }
    private fun marked(): List<Any?> {
        val index = stack.indexOfLast { it === marker }
        requireUi(index >= 0) { UiText(R.string.metadata_marker_missing) }
        val values = stack.subList(index + 1, stack.size).toList()
        stack.subList(index, stack.size).clear()
        return values
    }
    private fun tuple(count: Int) {
        require(stack.size >= count)
        val values = stack.subList(stack.size - count, stack.size).toList()
        repeat(count) { pop() }
        stack.add(values)
    }
    private fun symbol(module: String, name: String): Symbol {
        requireUi((module == "collections" && name == "OrderedDict") ||
            (module == "torch._utils" && name in setOf("_rebuild_tensor", "_rebuild_tensor_v2")) ||
            isParams(Symbol(module, name)) ||
            (module == "torch" && name in setOf("HalfStorage", "FloatStorage", "BFloat16Storage"))) {
            UiText(R.string.checkpoint_object_unsupported)
        }
        return Symbol(module, name)
    }
    @Suppress("UNCHECKED_CAST")
    fun read(): Map<*, *> {
        require(bytes.size <= 16 * 1024 * 1024)
        var operations = 0
        while (position < bytes.size) {
            requireUi(++operations < 2_000_000 && stack.size < 100_000 && memo.size < 1_000_000) { UiText(R.string.metadata_too_large) }
            when (val opcode = byte()) {
                0x80 -> requireUi(byte() in 2..5) { UiText(R.string.pytorch_format_unsupported) }
                0x95 -> require(unsigned(8) in 0..(bytes.size - position).toLong())
                '.'.code -> { require(stack.size == 1); return pop() as? Map<*, *> ?: errorUi(UiText(R.string.checkpoint_root_invalid)) }
                '('.code -> stack.add(marker)
                ')'.code -> stack.add(emptyList<Any?>())
                ']'.code -> stack.add(arrayListOf<Any?>())
                '}'.code -> stack.add(linkedMapOf<Any?, Any?>())
                'N'.code -> stack.add(null)
                0x88 -> stack.add(true)
                0x89 -> stack.add(false)
                'K'.code -> stack.add(byte().toLong())
                'M'.code -> stack.add(unsigned(2))
                'J'.code -> stack.add(unsigned(4).toInt().toLong())
                0x8a -> {
                    val length = byte(); require(length in 0..8)
                    val value = unsigned(length)
                    stack.add(if (length in 1..7 && value and (1L shl (length * 8 - 1)) != 0L)
                        value or (-1L shl (length * 8)) else value)
                }
                'G'.code -> { val raw = ByteArray(8) { byte().toByte() }; stack.add(ByteBuffer.wrap(raw).double) }
                'X'.code -> stack.add(text(unsigned(4).toInt()))
                0x8c -> stack.add(text(byte()))
                0x8d -> { val length = unsigned(8); require(length in 0..1_048_576); stack.add(text(length.toInt())) }
                'q'.code, 'r'.code -> { val key = if (opcode == 'q'.code) byte() else unsigned(4).toInt(); require(key in 0..1_000_000); memo[key] = stack.last() }
                0x94 -> memo[memo.size] = stack.last()
                'h'.code, 'j'.code -> { val key = if (opcode == 'h'.code) byte() else unsigned(4).toInt(); require(memo.containsKey(key)); stack.add(memo[key]) }
                't'.code -> stack.add(marked())
                0x85 -> tuple(1)
                0x86 -> tuple(2)
                0x87 -> tuple(3)
                0x81 -> {
                    val args = pop() as List<*>
                    val type = pop() as Symbol
                    requireUi(isParams(type) && args.isEmpty()) { UiText(R.string.config_object_unsupported) }
                    stack.add(Params()) // A plain map, never a Python constructor.
                }
                'c'.code -> stack.add(symbol(line(), line()))
                0x93 -> { val name = pop() as String; val module = pop() as String; stack.add(symbol(module, name)) }
                'Q'.code -> {
                    val id = pop() as List<*>
                    require(id.size == 5 && id[0] == "storage")
                    val type = id[1] as Symbol
                    require(type.module == "torch" && type.name.endsWith("Storage"))
                    val key = id[2] as String; val count = id[4] as Long
                    require(key.matches(Regex("[a-zA-Z0-9_]+")) && count in 1..1_073_741_824L)
                    stack.add(TorchStorage(type.name, key, count))
                }
                'R'.code -> {
                    val args = pop() as List<*>
                    val call = pop() as Symbol
                    stack.add(when (call) {
                        Symbol("collections", "OrderedDict") -> {
                            require(args.isEmpty()); linkedMapOf<Any?, Any?>()
                        }
                        Symbol("torch._utils", "_rebuild_tensor"), Symbol("torch._utils", "_rebuild_tensor_v2") -> {
                            require(args.size in 4..7)
                            TorchTensor(args[0] as TorchStorage, args[1] as Long,
                                (args[2] as List<*>).map { it as Long }, (args[3] as List<*>).map { it as Long })
                        }
                        else -> errorUi(UiText(R.string.weight_object_unsupported))
                    })
                }
                's'.code -> { val value = pop(); val key = pop(); require(key is String || key is Long); (stack.last() as MutableMap<Any?, Any?>)[key] = value }
                'u'.code -> {
                    val items = marked(); require(items.size % 2 == 0)
                    val map = stack.last() as MutableMap<Any?, Any?>
                    items.chunked(2).forEach { require(it[0] is String || it[0] is Long); map[it[0]] = it[1] }
                }
                'a'.code -> { val item = pop(); (stack.last() as MutableList<Any?>).add(item) }
                'e'.code -> { val items = marked(); (stack.last() as MutableList<Any?>).addAll(items) }
                'b'.code -> {
                    val state = pop() as? Map<*, *> ?: errorUi(UiText(R.string.metadata_unsupported))
                    val target = stack.last()
                    if (target is Params) {
                        require(state.keys.all { it is String })
                        target.putAll(state)
                    } else {
                        // PyTorch attaches OrderedDict._metadata; it is not a model tensor or callable.
                        require(target is MutableMap<*, *> && state.keys.all { it == "_metadata" })
                    }
                }
                else -> errorUi(UiText(R.string.checkpoint_opcode_unsupported, opcode))
            }
        }
        errorUi(UiText(R.string.metadata_unterminated))
    }
}

internal class TorchCheckpoint(file: File, weightsKey: String? = "weight") : Closeable {
    private val zip = ZipFile(file)
    private val prefix: String
    val root: Map<*, *>
    val weights: Map<*, *>
    init {
        try {
            val entries = zip.fileHeaders
            requireUi(entries.size <= 20_000 && entries.map { it.fileName }.distinct().size == entries.size) { UiText(R.string.checkpoint_zip_invalid) }
            val metadata = entries.singleOrNull { it.fileName.endsWith("/data.pkl") } ?: errorUi(UiText(R.string.pytorch_zip_required))
            requireUi(metadata.uncompressedSize in 1..16 * 1024 * 1024) { UiText(R.string.metadata_too_large) }
            prefix = metadata.fileName.removeSuffix("data.pkl")
            zip.getFileHeader(prefix + "byteorder")?.let { entry ->
                requireUi(entry.uncompressedSize in 1..16 && zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } == "little") { UiText(R.string.checkpoint_big_endian) }
            }
            root = TorchPickle(zip.getInputStream(metadata).use { input ->
                ByteArray(metadata.uncompressedSize.toInt()).also { DataInputStream(input).readFully(it); require(input.read() == -1) }
            }).read()
            weights = if (weightsKey == null) root else root[weightsKey] as? Map<*, *>
                ?: errorUi(UiText(R.string.checkpoint_weight_missing))
        } catch (error: Throwable) { zip.close(); throw error }
    }

    fun tensor(key: String): TorchTensor = (weights[key] as? TorchTensor
        ?: errorUi(UiText(R.string.checkpoint_key_missing, key))).also { tensor ->
        tensor.validate()
        val entry = zip.getFileHeader(prefix + "data/" + tensor.storage.key) ?: errorUi(UiText(R.string.weight_data_missing, key))
        requireUi(entry.uncompressedSize == Math.multiplyExact(tensor.storage.count, tensor.storage.bytesPerElement.toLong())) { UiText(R.string.weight_length_mismatch, key) }
    }

    fun open(tensor: TorchTensor): InputStream {
        val stream = zip.getInputStream(zip.getFileHeader(prefix + "data/" + tensor.storage.key))
        try {
            var remaining = Math.multiplyExact(tensor.offset, tensor.storage.bytesPerElement.toLong())
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) remaining -= skipped else { require(stream.read() != -1); remaining-- }
            }
            return stream
        } catch (error: Throwable) { stream.close(); throw error }
    }
    override fun close() = zip.close()
}
