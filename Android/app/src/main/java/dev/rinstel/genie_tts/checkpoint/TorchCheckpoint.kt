package dev.rinstel.genie_tts.checkpoint

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
        else -> error("不支持的权重精度：$type")
    }
}

internal data class TorchTensor(
    val storage: TorchStorage, val offset: Long, val shape: List<Long>, val stride: List<Long>,
) {
    val elements: Long get() = shape.fold(1L, Math::multiplyExact)

    fun validate() {
        require(shape.size == stride.size && shape.size <= 8 && shape.all { it > 0 }) { "权重张量形状无效。" }
        var contiguous = 1L
        for (index in shape.indices.reversed()) {
            require(shape[index] == 1L || stride[index] == contiguous) { "暂不支持非连续存储的权重，请使用 GPT-SoVITS 导出的完整模型。" }
            contiguous = Math.multiplyExact(contiguous, shape[index])
        }
        require(offset >= 0 && Math.addExact(offset, elements) <= storage.count) { "权重张量超出文件范围。" }
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
    private fun byte(): Int { require(position < bytes.size) { "权重元数据已截断。" }; return bytes[position++].toInt() and 255 }
    private fun unsigned(count: Int): Long {
        var value = 0L
        repeat(count) { value = value or (byte().toLong() shl (it * 8)) }
        return value
    }
    private fun text(length: Int): String {
        require(length in 0..1_048_576 && length <= bytes.size - position) { "权重元数据长度无效。" }
        return bytes.copyOfRange(position, position + length).toString(Charsets.UTF_8).also { position += length }
    }
    private fun line(): String {
        val start = position
        while (position < bytes.size && bytes[position] != 10.toByte()) position++
        require(position < bytes.size && position - start <= 4096) { "权重元数据格式无效。" }
        return bytes.copyOfRange(start, position++).toString(Charsets.UTF_8)
    }
    private fun pop(): Any? { require(stack.isNotEmpty()) { "权重元数据栈为空。" }; return stack.removeAt(stack.lastIndex) }
    private fun marked(): List<Any?> {
        val index = stack.indexOfLast { it === marker }
        require(index >= 0) { "权重元数据缺少标记。" }
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
        require((module == "collections" && name == "OrderedDict") ||
            (module == "torch._utils" && name in setOf("_rebuild_tensor", "_rebuild_tensor_v2")) ||
            isParams(Symbol(module, name)) ||
            (module == "torch" && name in setOf("HalfStorage", "FloatStorage", "BFloat16Storage"))) {
            "该文件包含不支持的对象，请使用 GPT-SoVITS 导出的完整 .ckpt / .pth 权重。"
        }
        return Symbol(module, name)
    }
    @Suppress("UNCHECKED_CAST")
    fun read(): Map<*, *> {
        require(bytes.size <= 16 * 1024 * 1024)
        var operations = 0
        while (position < bytes.size) {
            require(++operations < 2_000_000 && stack.size < 100_000 && memo.size < 1_000_000) { "权重元数据过大。" }
            when (val opcode = byte()) {
                0x80 -> require(byte() in 2..5) { "不支持此 PyTorch 保存格式。" }
                0x95 -> require(unsigned(8) in 0..(bytes.size - position).toLong())
                '.'.code -> { require(stack.size == 1); return pop() as? Map<*, *> ?: error("权重根目录格式无效。") }
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
                    require(isParams(type) && args.isEmpty()) { "不支持此配置对象。" }
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
                        else -> error("不支持的权重对象。")
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
                    val state = pop() as? Map<*, *> ?: error("不支持的权重元数据。")
                    val target = stack.last()
                    if (target is Params) {
                        require(state.keys.all { it is String })
                        target.putAll(state)
                    } else {
                        // PyTorch attaches OrderedDict._metadata; it is not a model tensor or callable.
                        require(target is MutableMap<*, *> && state.keys.all { it == "_metadata" })
                    }
                }
                else -> error("不支持的权重保存格式（指令 $opcode），请使用 GPT-SoVITS 导出的完整权重。")
            }
        }
        error("权重元数据未完整结束。")
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
            require(entries.size <= 20_000 && entries.map { it.fileName }.distinct().size == entries.size) { "权重 ZIP 目录无效。" }
            val metadata = entries.singleOrNull { it.fileName.endsWith("/data.pkl") } ?: error("请选择 PyTorch ZIP 格式的完整权重。")
            require(metadata.uncompressedSize in 1..16 * 1024 * 1024) { "权重元数据过大。" }
            prefix = metadata.fileName.removeSuffix("data.pkl")
            zip.getFileHeader(prefix + "byteorder")?.let { entry ->
                require(entry.uncompressedSize in 1..16 && zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } == "little") { "暂不支持大端权重。" }
            }
            root = TorchPickle(zip.getInputStream(metadata).use { input ->
                ByteArray(metadata.uncompressedSize.toInt()).also { DataInputStream(input).readFully(it); require(input.read() == -1) }
            }).read()
            weights = if (weightsKey == null) root else root[weightsKey] as? Map<*, *>
                ?: error("缺少 weight，请选择 GPT-SoVITS 导出的模型，而非训练过程快照。")
        } catch (error: Throwable) { zip.close(); throw error }
    }

    fun tensor(key: String): TorchTensor = (weights[key] as? TorchTensor
        ?: error("模型与 V2ProPlus 不匹配，缺少权重：$key")).also { tensor ->
        tensor.validate()
        val entry = zip.getFileHeader(prefix + "data/" + tensor.storage.key) ?: error("权重数据缺失：$key")
        require(entry.uncompressedSize == Math.multiplyExact(tensor.storage.count, tensor.storage.bytesPerElement.toLong())) { "权重数据长度不匹配：$key" }
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
