package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.GenerationContext

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Cache timestep-only projections once; keep the four-step solver unchanged. */
internal class V5DiTRuntime(
    private val environment: OrtEnvironment,
    private val session: (String) -> OrtSession,
    private val releaseSession: (String) -> Unit,
    private val directory: File,
    private val qnnRuntime: () -> File?,
    private val qnnPerformance: String,
    private val trace: InferenceTraceLogger,
    private val onNpuUnavailable: () -> Unit,
) : AutoCloseable {
    private val timer = InferenceTimer(trace)
    // ORT's WebGPU command encoder is shared across sessions. CPU edges and HTP stay outside this lock.
    val gpuLock = Any()
    private var timeConditions: List<Pair<FloatBuffer, FloatBuffer>>? = null
    private var timeTensors: List<Pair<OnnxTensor, OnnxTensor>>? = null
    // Keep one HTP-sized workspace and one unpadded GPU tail; never cache every sentence length.
    private var workspace: Workspace? = null
    private var tailWorkspace: Workspace? = null
    private val qnn = mutableMapOf<Int, QnnSession?>()
    private val preparedBuckets = mutableSetOf<Int>()
    private val hasHtpGraphs = hasHtpGraphs(directory)
    private val qnnModel by lazy(LazyThreadSafetyMode.NONE) {
        QnnSession.Model(requireNotNull(qnnRuntime()), File(directory, HTP_BODY),
            File(directory, "v5_sovits_fp32.bin"), "v5")
    }
    var lastUsedNpu = false
        private set

    /** Prepare this utterance's shapes before rendering any acoustic chunk. */
    fun prepareNpu(buckets: List<Int>, progress: (Int, Int, Boolean) -> Unit) {
        if (!hasHtpGraphs || qnnRuntime() == null) return
        val pending = buckets.filterNot { it in preparedBuckets }
        if (pending.isEmpty()) return
        try {
            for ((index, bucket) in pending.withIndex()) {
                GenerationContext.checkCurrent()
                progress(index, pending.size, false)
                qnn[bucket] = timer.measure("v5_qnn_session_ms") {
                    QnnSession(qnnModel, qnnPerformance, bucket, allowCompilation = true,
                        onCompile = { progress(index, pending.size, true) })
                }
                preparedBuckets.add(bucket)
            }
            GenerationContext.checkCurrent()
            progress(pending.size, pending.size, false)
        } catch (error: Exception) {
            close()
            GenerationContext.checkCurrent()
            android.util.Log.w("VoiceBoxV5", "HTP preparation failed, using FP32 WebGPU", error)
            onNpuUnavailable()
        }
    }

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
        val bucket = bucketFor(frames)
        if (bucket in preparedBuckets && qnnRuntime() != null && !qnn.containsKey(bucket)) {
            // Feature preparation and memory trimming can evict live sessions.
            // Reload their prepared contexts; source compilation is forbidden here.
            qnn[bucket] = timer.measure("v5_qnn_context_reload_ms") {
                try { QnnSession(qnnModel, qnnPerformance, bucket) }
                catch (error: Exception) {
                    GenerationContext.checkCurrent()
                    android.util.Log.w("VoiceBoxV5", "HTP context unavailable, using FP32 WebGPU", error)
                    onNpuUnavailable()
                    null
                }
            }
        }
        val htp = if (qnnRuntime() != null) qnn[bucket] else null
        val refine = htp != null
        lastUsedNpu = htp != null
        val length = if (htp == null) frames else bucket
        val work = workspace?.takeIf { it.frames == length && it.split == refine }
            ?: Workspace(length, refine).also { workspace?.close(); workspace = it }
        val buffers = work.buffers
        val x = buffers.getValue("x")
        for (c in 0 until 100) for (f in 0 until length) {
            x.put(c * length + f, if (f < frames) state.get(c * frames + f) else 0f)
        }
        buffers.getValue("condition").apply {
            clear(); put(condition.floatBuffer)
            while (hasRemaining()) put(0f)
            rewind()
        }
        val angles = rope.floatBuffer
        val cosine = buffers.getValue("cos")
        val sine = buffers.getValue("sin")
        for (i in 0 until length * 64) {
            cosine.put(i, if (i < frames * 64) cos(angles.get(i)) else 0f)
            sine.put(i, if (i < frames * 64) sin(angles.get(i)) else 0f)
        }
        val mask = buffers.getValue("mask")
        for (i in 0 until length) mask.put(i, if (i < frames) 1f else 0f)
        trace.event(InferenceTraceEvent.Model(InferenceTraceRecord(InferenceModelRole.VOCODER,
            if (htp == null) InferenceProvider.WEBGPU else InferenceProvider.QNN)))
        val gpu = timer.measure("v5_dit_session_ms") { session(if (refine) BODY else GRAPH) }
        val stem = if (refine) session(STEM) else null
        val head = if (refine) session(HEAD) else null
        val finalWork = if (refine && frames != length) {
            tailWorkspace?.takeIf { it.frames == frames }
                ?: Workspace(frames, true).also { tailWorkspace?.close(); tailWorkspace = it }
        } else work
        val times = timeTensors ?: run {
            val owned = mutableListOf<OnnxTensor>()
            try {
                temporal.map { (modulation, final) ->
                    OnnxTensor.createTensor(environment, modulation, longArrayOf(22, 6, 1024)).also(owned::add) to
                        OnnxTensor.createTensor(environment, final, longArrayOf(2, 1024)).also(owned::add)
                }
            } catch (error: Throwable) { owned.forEach { it.close() }; throw error }
        }.also { timeTensors = it }
        for (step in 0 until 4) {
            GenerationContext.checkCurrent()
            buffers["modulation"] = temporal[step].first
            buffers["final"] = temporal[step].second
            val current = if (refine && step == 3) finalWork else work
            var npuFailure: Exception? = null
            timer.measure("v5_dit_ms") inference@{
                val (modulation, final) = times[step]
                val inputs = current.tensors + mapOf("modulation" to modulation, "final" to final)
                if (refine && step < 3) {
                    timer.measure("v5_dit_edges_ms") { runModel(stem!!, inputs) }
                    try {
                        timer.measure("v5_dit_htp_ms") { htp.step(buffers, buffers.getValue("body_hidden")) }
                    } catch (error: Exception) {
                        GenerationContext.checkCurrent()
                        npuFailure = error
                        return@inference
                    }
                    timer.measure("v5_dit_edges_ms") { runModel(head!!, inputs) }
                } else timer.measure("v5_dit_fp32_ms") {
                    if (refine) {
                        if (current !== work) current.copyInputsFrom(work)
                        runModel(stem!!, inputs)
                        timer.measure("v5_dit_gpu_body_ms") {
                            synchronized(gpuLock) { runModel(gpu, inputs) }
                        }
                        runModel(head!!, inputs)
                    } else synchronized(gpuLock) { runModel(gpu, inputs) }
                }
            }
            if (npuFailure != null) {
                android.util.Log.w("VoiceBoxV5", "HTP run failed, retrying acoustic chunk on FP32 WebGPU", npuFailure)
                qnn.remove(bucket)?.close()
                qnn[bucket] = null
                onNpuUnavailable()
                // The caller's state is only updated after all four steps succeed.
                solve(state, condition, rope, frames, referenceFrames, progress)
                return
            }
            for (c in 0 until 100) for (f in referenceFrames until frames) {
                val index = c * length + f
                x.put(index, x.get(index) + .25f * current.output.get(c * current.frames + f))
            }
            progress(step + 1)
        }
        for (c in 0 until 100) for (f in referenceFrames until frames) state.put(c * frames + f, x.get(c * length + f))
    }

    private fun runModel(model: OrtSession, inputs: Map<String, OnnxTensor>) {
        // Pinned tensors belong to the workspace; Result.close() does not release them.
        model.runCancellable(model.inputNames.associateWith { inputs.getValue(it) },
            model.outputNames.associateWith { inputs.getValue(it) }).use { }
    }

    private inner class Workspace(val frames: Int, val split: Boolean) : AutoCloseable {
        private val shapes = linkedMapOf("x" to longArrayOf(1, 100, frames.toLong()),
            "condition" to longArrayOf(1, frames.toLong(), 1024), "cos" to longArrayOf(1, frames.toLong(), 64),
            "sin" to longArrayOf(1, frames.toLong(), 64), "mask" to longArrayOf(1, frames.toLong(), 1)).apply {
            if (split) for (name in listOf("stem_hidden", "body_hidden")) put(name, longArrayOf(1, frames.toLong(), 1024))
        }
        val buffers = shapes.mapValuesTo(linkedMapOf()) { (_, shape) -> direct(shape.fold(1L) { a, b -> a * b }.toInt()) }
        val tensors = linkedMapOf<String, OnnxTensor>()
        val output = direct(100 * frames)

        init {
            try {
                shapes.forEach { (name, shape) -> tensors[name] = OnnxTensor.createTensor(environment, buffers.getValue(name), shape) }
                tensors["velocity"] = OnnxTensor.createTensor(environment, output, longArrayOf(1, 100, frames.toLong()))
            }
            catch (error: Throwable) { close(); throw error }
        }

        fun copyInputsFrom(source: Workspace) {
            val x = buffers.getValue("x")
            for (c in 0 until 100) for (f in 0 until frames) x.put(c * frames + f, source.buffers.getValue("x").get(c * source.frames + f))
            for (name in listOf("condition", "cos", "sin", "mask")) {
                val target = buffers.getValue(name)
                target.clear(); target.put(source.buffers.getValue(name).duplicate().apply { clear(); limit(target.capacity()) }); target.rewind()
            }
        }

        override fun close() { tensors.values.forEach { it.close() }; tensors.clear() }
    }

    override fun close() {
        qnn.values.forEach { it?.close() }; qnn.clear()
        workspace?.close(); workspace = null
        tailWorkspace?.close(); tailWorkspace = null
        timeTensors?.forEach { (modulation, final) -> modulation.close(); final.close() }; timeTensors = null
    }

    companion object {
        internal fun requiredBuckets(referenceFrames: Int, targetFrames: Int): List<Int> {
            val chunkFrames = minOf(1000 - referenceFrames, 640)
            return listOf(minOf(chunkFrames, targetFrames), targetFrames % chunkFrames)
                .filter { it > 0 }.map { bucketFor(referenceFrames + it) }.distinct()
        }
        private fun bucketFor(frames: Int) = when { frames <= 512 -> 512; frames <= 768 -> 768; else -> 1000 }
        const val GRAPH = "v5-dit-compact.onnx"
        const val TIME = "v5-time.onnx"
        const val STEM = "v5-dit-stem.onnx"
        const val BODY = "v5-dit-body.onnx"
        const val HTP_BODY = "v5-dit-htp.onnx"
        const val HEAD = "v5-dit-head.onnx"
        val SPLIT_GRAPHS = listOf(STEM, BODY, HEAD)
        fun hasHtpGraphs(directory: File) = (SPLIT_GRAPHS + listOf(HTP_BODY, GRAPH, TIME)).all { File(directory, it).isFile }
        private fun direct(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun copy(source: FloatBuffer) = direct(source.remaining()).apply { put(source); rewind() }
    }
}
