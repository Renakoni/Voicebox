package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Opt-in fixed-input capture for numerical replay; ordinary generation never writes tensors. */
internal interface V5AcousticDiagnostics : InferenceTraceLogger {
    fun acousticInputs(inputs: Map<String, FloatBuffer>, frames: Int, referenceFrames: Int)
}

/** Cache timestep-only projections once; keep the four-step solver unchanged. */
internal class V5DiTRuntime(
    private val environment: OrtEnvironment,
    private val session: (String) -> OrtSession,
    private val releaseSession: (String) -> Unit,
    private val directory: File,
    private val qnnRuntime: File?,
    private val qnnPerformance: String,
    private val trace: InferenceTraceLogger,
) : AutoCloseable {
    private val timer = InferenceTimer(trace)
    private var timeConditions: List<Pair<FloatBuffer, FloatBuffer>>? = null
    private val qnn = mutableMapOf<Int, V5QnnSession?>()
    var lastUsedNpu = false
        private set

    fun solve(state: FloatBuffer, condition: OnnxTensor, rope: OnnxTensor, frames: Int, referenceFrames: Int,
              progress: (Int) -> Unit) {
        val temporal = timeConditions ?: timer.measure("v5_time_cache_ms") {
            try {
                val model = session(TIME)
                (0 until 4).map { step ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(step / 4f)), longArrayOf(1)).use { time ->
                        model.runCancellable(mapOf("time" to time)).use { result ->
                            copy((result.get(0) as OnnxTensor).floatBuffer) to copy((result.get(1) as OnnxTensor).floatBuffer)
                        }
                    }
                }
            } finally { releaseSession(TIME) }
        }.also { timeConditions = it }
        val bucket = when { frames <= 512 -> 512; frames <= 768 -> 768; else -> 1000 }
        if (!qnn.containsKey(bucket)) {
            qnn[bucket] = null
            if (qnnRuntime != null && File(qnnRuntime, "libonnxruntime-qnn.so").isFile) {
                qnn[bucket] = timer.measure("v5_qnn_session_ms") {
                    try { V5QnnSession(qnnRuntime, File(directory, GRAPH), qnnPerformance, bucket) }
                    catch (error: Exception) {
                        GenerationContext.checkCurrent()
                        android.util.Log.w("VoiceboxV5", "HTP unavailable, using FP32 WebGPU", error)
                        null
                    }
                }
            }
        }
        val htp = qnn[bucket]
        lastUsedNpu = htp != null
        val length = if (htp == null) frames else bucket
        val x = direct(100 * length)
        for (c in 0 until 100) for (f in 0 until frames) x.put(c * length + f, state.get(c * frames + f))
        val cond = direct(length * 1024).apply { put(condition.floatBuffer); rewind() }
        val angles = rope.floatBuffer
        val cosine = direct(length * 64)
        val sine = direct(length * 64)
        for (i in 0 until frames * 64) { cosine.put(i, cos(angles.get(i))); sine.put(i, sin(angles.get(i))) }
        val mask = direct(length)
        for (i in 0 until frames) mask.put(i, 1f)
        val buffers = linkedMapOf("x" to x, "condition" to cond, "cos" to cosine, "sin" to sine, "mask" to mask)
        if (trace is V5AcousticDiagnostics) trace.acousticInputs(buffers + ("rope" to angles), frames, referenceFrames)
        val shapes = mapOf("x" to longArrayOf(1,100,length.toLong()), "condition" to longArrayOf(1,length.toLong(),1024),
            "cos" to longArrayOf(1,length.toLong(),64), "sin" to longArrayOf(1,length.toLong(),64),
            "mask" to longArrayOf(1,length.toLong(),1), "modulation" to longArrayOf(22,6,1024), "final" to longArrayOf(2,1024))
        trace.event(InferenceTraceEvent.Model(InferenceTraceRecord(InferenceModelRole.VOCODER,
            if (htp == null) InferenceProvider.WEBGPU else InferenceProvider.QNN)))
        val gpu = if (htp == null) timer.measure("v5_dit_session_ms") { session(GRAPH) } else null
        val gpuInputs = linkedMapOf<String, OnnxTensor>()
        val output = direct(100 * length)
        try {
            if (gpu != null) buffers.forEach { (name, data) ->
                gpuInputs[name] = OnnxTensor.createTensor(environment, data, shapes.getValue(name))
            }
            for (step in 0 until 4) {
                GenerationContext.checkCurrent()
                buffers["modulation"] = temporal[step].first
                buffers["final"] = temporal[step].second
                timer.measure("v5_dit_ms") {
                    if (htp != null) htp.step(buffers, output)
                    else {
                        OnnxTensor.createTensor(environment, temporal[step].first, shapes.getValue("modulation")).use { mod ->
                            OnnxTensor.createTensor(environment, temporal[step].second, shapes.getValue("final")).use { final ->
                                gpu!!.runCancellable(gpuInputs + mapOf("modulation" to mod, "final" to final)).use { result ->
                                    output.clear(); output.put((result.get(0) as OnnxTensor).floatBuffer); output.rewind()
                                }
                            }
                        }
                    }
                }
                for (c in 0 until 100) for (f in referenceFrames until frames) {
                    val index = c * length + f
                    x.put(index, x.get(index) + .25f * output.get(index))
                }
                progress(step + 1)
            }
            for (c in 0 until 100) for (f in referenceFrames until frames) state.put(c * frames + f, x.get(c * length + f))
        } finally { gpuInputs.values.forEach { it.close() } }
    }

    override fun close() { qnn.values.forEach { it?.close() }; qnn.clear() }

    companion object {
        const val GRAPH = "v5-dit-compact.onnx"
        const val TIME = "v5-time.onnx"
        private fun direct(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun copy(source: FloatBuffer) = direct(source.remaining()).apply { put(source); rewind() }
    }
}
