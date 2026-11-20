package dev.renakoni.voicebox.model.ming

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object MingWebGpu {
    @JvmStatic private external fun probe(graph: String, input: String, output: String): Int

    /** Run the shipped Ming ORT with CPU fallback disabled and check the four ReLU outputs. */
    fun available(directory: File, work: File): Boolean {
        System.load(File(directory, "libonnxruntime.so").absolutePath)
        System.load(File(directory, "libming-synthesize.so").absolutePath)
        val input = File(work, "probe-input.f32")
        val output = File(work, "probe-output.f32")
        val values = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        values.asFloatBuffer().put(floatArrayOf(-2f, 3f, -4f, 5f))
        input.writeBytes(values.array())
        val result = probe(File(directory, "webgpu-probe.onnx").absolutePath, input.absolutePath, output.absolutePath)
        val expected = floatArrayOf(0f, 3f, 0f, 5f)
        val passed = result == 0 && output.length() == 16L && ByteBuffer.wrap(output.readBytes())
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { actual -> expected.indices.all { actual[it] == expected[it] } }
        Log.i("MingWorker", "WebGPU probe result=$result verified=$passed")
        return passed
    }
}
