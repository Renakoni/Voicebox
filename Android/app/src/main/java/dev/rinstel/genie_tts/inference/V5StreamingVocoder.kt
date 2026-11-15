package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Overlap HTP flow with FP32 GPU audio, retaining both sides of the convolution receptive field. */
internal class V5StreamingVocoder(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val timer: InferenceTimer,
) : AutoCloseable {
    private val context = GenerationContext.current()
    private val worker = Executors.newSingleThreadExecutor()
    private var pending: Future<FloatArray>? = null
    private var tail = FloatArray(0)
    private var left = 0
    private val audio = mutableListOf<FloatArray>()

    fun submit(mel: FloatTensorData, last: Boolean) {
        collect()
        val frames = mel.shape[2].toInt()
        val tailFrames = tail.size / 100
        val count = tailFrames + frames
        val values = FloatArray(100 * count)
        for (channel in 0 until 100) {
            tail.copyInto(values, channel * count, channel * tailFrames, (channel + 1) * tailFrames)
            mel.values.copyInto(values, channel * count + tailFrames, channel * frames, (channel + 1) * frames)
        }
        val begin = left
        val end = if (last) count else count - HALO
        require(end >= begin)
        pending = worker.submit<FloatArray> {
            val render = {
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 100, count.toLong())).use { input ->
                    timer.measure("v5_hifigan_ms") { session.runCancellable(mapOf("mel" to input)) }.use { output ->
                        val samples = (output.get(0) as OnnxTensor).floatBuffer
                        samples.position(begin * HOP)
                        FloatArray((end - begin) * HOP).also { samples.get(it) }
                    }
                }
            }
            if (context == null) render() else context.run(render)
        }
        if (!last) {
            val keep = minOf(2 * HALO, count)
            tail = FloatArray(100 * keep)
            for (channel in 0 until 100) values.copyInto(tail, channel * keep,
                channel * count + count - keep, (channel + 1) * count)
            left = keep - HALO
        }
    }

    fun finish(): FloatArray {
        collect()
        val result = FloatArray(audio.sumOf { it.size })
        var offset = 0
        for (part in audio) { part.copyInto(result, offset); offset += part.size }
        return result
    }

    private fun collect() {
        val job = pending ?: return
        pending = null
        try { audio += job.get() }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
    }

    override fun close() {
        // Always join before the backend can release the session, including a failed/cancelled flow.
        try { collect() } finally { worker.shutdown() }
    }

    companion object {
        // V5's five upsample stages and largest (11, dilations 1/3/5) residual kernels
        // need fewer than 16 input mel frames on either side. No crossfade or zero padding is added.
        private const val HALO = 16
        private const val HOP = 480
    }
}
