package dev.rinstel.genie_tts.inference

import android.os.Build
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Optional Qualcomm runtime; no ORT objects are shared with the WebGPU library. */
internal class V5QnnSession(runtime: File, model: File, performance: String, frames: Int,
                            allowCompilation: Boolean = false) : AutoCloseable {
    private val handle: Long
    private val names: Array<String>

    init {
        check(NativeDecoderLoop.isAvailable())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Build.FINGERPRINT.toByteArray())
        digest.update(model.readBytes())
        // Import replaces the model directory atomically. Metadata also invalidates local
        // weight/runtime replacements without re-reading gigabytes for each shape bucket.
        val dependencies = listOf(File(model.parentFile, "v5_sovits_fp32.bin")) +
            runtime.listFiles().orEmpty().filter { it.extension == "so" }.sortedBy { it.name }
        dependencies.forEach { file ->
            digest.update("\n${file.name}:${file.length()}:${file.lastModified()}".toByteArray())
        }
        File(runtime, "libonnxruntime-qnn.so").inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val key = digest.digest().take(12).joinToString("") { "%02x".format(it) }
        val cache = File(model.parentFile, ".v5-htp-$key-$frames.onnx")
        // Retain only this model/runtime's three bounded shape contexts.
        model.parentFile!!.listFiles()?.filter { it.name.startsWith(".v5-htp-") && !it.name.startsWith(".v5-htp-$key-") }
            ?.forEach { it.delete() }
        // Generation loads only a prepared context. Even if it disappears, this
        // path cannot compile the source graph while rendering an acoustic chunk.
        fun load() = if (allowCompilation) {
            create(runtime.absolutePath, model.absolutePath, cache.absolutePath, performance, frames)
        } else {
            create(runtime.absolutePath, cache.absolutePath, "", performance, frames)
        }
        handle = try { load() }
        catch (error: IllegalStateException) {
            if (!allowCompilation || !cache.delete()) throw error
            load()
        }
        names = try { inputNames(handle) }
        catch (error: Throwable) { destroy(handle); throw error }
    }

    fun step(inputs: Map<String, FloatBuffer>, output: FloatBuffer) {
        terminate(handle, false)
        val context = GenerationContext.current()
        val registration = context?.onCancel { terminate(handle, true) }
        try {
            run(handle, names.map { inputs.getValue(it) }.toTypedArray(), output)
            context?.checkCancelled()
        } catch (error: IllegalStateException) {
            context?.checkCancelled()
            throw error
        } finally { registration?.close() }
    }

    override fun close() = destroy(handle)

    companion object {
        /** A real HTP run with CPU fallback disabled; a chip name alone is not proof of support. */
        internal fun probe(runtime: File, model: File) {
            check(NativeDecoderLoop.isAvailable())
            val handle = create(runtime.absolutePath, model.absolutePath, "", "balanced", 512)
            try {
                fun buffer() = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val input = buffer().apply { put(floatArrayOf(1f, 2f, 3f, 4f)); rewind() }
                val output = buffer()
                check(inputNames(handle).contentEquals(arrayOf("x")))
                run(handle, arrayOf(input), output)
                for (i in 0 until 4) check(kotlin.math.abs(output.get(i) - ((i + 1) * .5f + .25f)) < .01f)
            } finally { destroy(handle) }
        }

        @JvmStatic private external fun create(runtime: String, model: String, cache: String, performance: String, frames: Int): Long
        @JvmStatic private external fun inputNames(handle: Long): Array<String>
        @JvmStatic private external fun run(handle: Long, inputs: Array<FloatBuffer>, output: FloatBuffer)
        @JvmStatic private external fun terminate(handle: Long, cancel: Boolean)
        @JvmStatic private external fun destroy(handle: Long)
    }
}
