package dev.rinstel.genie_tts.inference

import android.os.Build
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Optional Qualcomm runtime; no ORT objects are shared with the WebGPU library. */
internal class V5QnnSession(runtime: File, model: File, performance: String, frames: Int) : AutoCloseable {
    private val handle: Long
    private val names: Array<String>

    init {
        check(NativeDecoderLoop.isAvailable())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Build.FINGERPRINT.toByteArray())
        digest.update(model.readBytes())
        File(runtime, "libonnxruntime-qnn.so").inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val key = digest.digest().take(12).joinToString("") { "%02x".format(it) }
        val cache = File(model.parentFile, ".v5-htp-$key-$frames.onnx")
        // Retain only this model/runtime's three bounded shape contexts.
        model.parentFile!!.listFiles()?.filter { it.name.startsWith(".v5-htp-") && !it.name.startsWith(".v5-htp-$key-") }
            ?.forEach { it.delete() }
        handle = try { create(runtime.absolutePath, model.absolutePath, cache.absolutePath, performance, frames) }
        catch (error: IllegalStateException) {
            if (!cache.delete()) throw error
            create(runtime.absolutePath, model.absolutePath, cache.absolutePath, performance, frames)
        }
        names = inputNames(handle)
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
        @JvmStatic private external fun create(runtime: String, model: String, cache: String, performance: String, frames: Int): Long
        @JvmStatic private external fun inputNames(handle: Long): Array<String>
        @JvmStatic private external fun run(handle: Long, inputs: Array<FloatBuffer>, output: FloatBuffer)
        @JvmStatic private external fun terminate(handle: Long, cancel: Boolean)
        @JvmStatic private external fun destroy(handle: Long)
    }
}
