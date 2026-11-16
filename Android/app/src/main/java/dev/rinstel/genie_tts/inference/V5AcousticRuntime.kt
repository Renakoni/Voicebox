package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

/** V5 Turbo's four Euler steps, with upstream reference anchoring and 48 kHz HiFiGAN. */
internal class V5AcousticRuntime(
    private val environment: OrtEnvironment,
    private val session: (String) -> OrtSession,
    traceLogger: InferenceTraceLogger,
    private val accelerated: V5DiTRuntime? = null,
    private val progress: (Int, Int) -> Unit,
) {
    private val timer = InferenceTimer(traceLogger)
    private data class Reference(
        val audio: FloatArray,
        val phones: LongArray,
        val ge: FloatTensorData,
        val features: FloatTensorData,
        val mel: FloatTensorData,
    )

    private var reference: Reference? = null

    fun generate(input: TtsPreparedInput, semantic: OnnxTensor, promptCodes: LongArray): TtsGenerationResult {
        val audio = requireNotNull(input.refAudio32k)
        val ref = reference?.takeIf { it.audio === audio.values && it.phones === input.refSeq.values }
            ?: timer.measure("v5_reference_ms") { prepareReference(input, promptCodes) }.also { reference = it }
        val target = timer.measure("v5_condition_ms") { condition(semantic, input.textSeq, ref.ge) }
        val dit = if (accelerated == null) timer.measure("v5_dit_session_ms") { session("v5-dit.onnx") } else null
        val referenceFrames = ref.mel.shape[2].toInt()
        val targetFrames = target.shape[2].toInt()
        val chunkFrames = minOf(1000 - referenceFrames, 640)
        val chunkCount = (targetFrames + chunkFrames - 1) / chunkFrames
        val results = mutableListOf<FloatTensorData>()
        var rollingMel = ref.mel
        var rollingFeatures = ref.features
        var start = 0
        val random = Random()
        var streaming: V5StreamingVocoder? = null
        try {
            while (start < targetFrames) {
                GenerationContext.checkCurrent()
                val chunk = target.slice(start, minOf(start + chunkFrames, targetFrames))
                val tail = minOf(32, referenceFrames, rollingMel.shape[2].toInt(), rollingFeatures.shape[2].toInt())
                val prompt = if (start == 0) ref.mel else concat(ref.mel.slice(0, referenceFrames - tail), rollingMel.last(tail))
                val refFeatures = if (start == 0) ref.features else concat(ref.features.slice(0, referenceFrames - tail), rollingFeatures.last(tail))
                val features = concat(refFeatures, chunk)
                val length = features.shape[2].toInt()
                val paddedPrompt = concat(prompt, FloatTensorData(FloatArray(100 * (length - referenceFrames)),
                    longArrayOf(1, 100, (length - referenceFrames).toLong())))
                floatTensor(paddedPrompt).use { promptTensor ->
                    floatTensor(features).use { featuresTensor ->
                        timer.measure("v5_static_ms") {
                            session("v5-static.onnx").runCancellable(mapOf("prompt" to promptTensor, "features" to featuresTensor))
                        }.use { static ->
                            // The same direct input buffer and static OrtValues serve all four steps.
                            val state = ByteBuffer.allocateDirect(100 * length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                            for (channel in 0 until 100) for (frame in referenceFrames until length) {
                                state.put(channel * length + frame, (random.nextGaussian() * 0.875).toFloat())
                            }
                            if (accelerated != null) {
                                accelerated.solve(state, static.get(0) as OnnxTensor, static.get(1) as OnnxTensor,
                                    length, referenceFrames) { step -> progress(results.size * 4 + step, chunkCount * 4) }
                            } else OnnxTensor.createTensor(environment, state, longArrayOf(1, 100, length.toLong())).use { x ->
                                val time = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                                OnnxTensor.createTensor(environment, time, longArrayOf(1)).use { timeTensor ->
                                    for (step in 0 until 4) {
                                        GenerationContext.checkCurrent()
                                        time.put(0, step / 4f)
                                        timer.measure("v5_dit_ms") {
                                            dit!!.runCancellable(mapOf("x" to x,
                                                "condition" to (static.get(0) as OnnxTensor), "rope" to (static.get(1) as OnnxTensor),
                                                "time" to timeTensor))
                                        }.use { result ->
                                            val velocity = (result.get(0) as OnnxTensor).floatBuffer
                                            for (channel in 0 until 100) for (frame in referenceFrames until length) {
                                                val index = channel * length + frame
                                                state.put(index, state.get(index) + .25f * velocity.get(index))
                                            }
                                        }
                                        progress(results.size * 4 + step + 1, chunkCount * 4)
                                    }
                                }
                            }
                            val generated = FloatArray(100 * (length - referenceFrames))
                            for (channel in 0 until 100) for (frame in referenceFrames until length) {
                                generated[channel * (length - referenceFrames) + frame - referenceFrames] = state.get(channel * length + frame)
                            }
                            val mel = FloatTensorData(generated, longArrayOf(1, 100, (length - referenceFrames).toLong()))
                            results += mel
                            if (streaming == null && results.size == 1 && chunkCount > 1 && accelerated?.lastUsedNpu == true) {
                                val vocoder = timer.measure("v5_vocoder_session_ms") { session(VOCODER) }
                                streaming = V5StreamingVocoder(environment, vocoder, timer, accelerated.gpuLock)
                            }
                            streaming?.submit(mel, last = start + chunkFrames >= targetFrames)
                            rollingMel = mel.last(minOf(referenceFrames, mel.shape[2].toInt()))
                            rollingFeatures = chunk.last(minOf(referenceFrames, chunk.shape[2].toInt()))
                        }
                    }
                }
                start += chunkFrames
            }
            streaming?.let {
                val samples = it.finish()
                return TtsGenerationResult(samples, longArrayOf(samples.size.toLong()), sampleRate = 48000)
            }
            val mel = concat(*results.toTypedArray())
            val vocoder = timer.measure("v5_vocoder_session_ms") { session(VOCODER) }
            return floatTensor(mel).use { tensor ->
                timer.measure("v5_hifigan_ms") { vocoder.runCancellable(mapOf("mel" to tensor)) }.use { result ->
                    val output = result.get(0) as OnnxTensor
                    val samples = FloatArray(output.info.numElements.toInt())
                    output.floatBuffer.get(samples)
                    TtsGenerationResult(samples, output.info.shape, sampleRate = 48000)
                }
            }
        } finally { streaming?.close() }
    }

    private fun prepareReference(input: TtsPreparedInput, codes: LongArray): Reference {
        val audio = requireNotNull(input.refAudio32k)
        return floatTensor(audio).use { waveform ->
            session("v5-reference-audio.onnx").runCancellable(mapOf("audio" to waveform)).use { result ->
                val ge = copy(result.get(0) as OnnxTensor)
                val mel = copy(result.get(1) as OnnxTensor)
                OnnxTensor.createTensor(environment, LongBuffer.wrap(codes), longArrayOf(1, 1, codes.size.toLong())).use { semantic ->
                    val features = condition(semantic, input.refSeq, ge)
                    val frames = minOf(mel.shape[2], features.shape[2]).toInt()
                    val start = maxOf(0, frames - 500)
                    Reference(audio.values, input.refSeq.values, ge, features.slice(start, frames), mel.slice(start, frames))
                }
            }
        }
    }

    private fun condition(codes: OnnxTensor, phones: LongTensorData, ge: FloatTensorData): FloatTensorData =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(phones.values), phones.shape).use { text ->
            floatTensor(ge).use { embedding ->
                session("v5-condition.onnx").runCancellable(mapOf("codes" to codes, "phones" to text, "ge" to embedding)).use {
                    copy(it.get(0) as OnnxTensor)
                }
            }
        }

    private fun floatTensor(data: FloatTensorData): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data.values), data.shape)

    private fun copy(tensor: OnnxTensor): FloatTensorData = FloatTensorData(
        FloatArray(tensor.info.numElements.toInt()).also { tensor.floatBuffer.get(it) }, tensor.info.shape)

    private fun FloatTensorData.last(frames: Int) = slice(shape[2].toInt() - frames, shape[2].toInt())

    private fun FloatTensorData.slice(start: Int, end: Int): FloatTensorData {
        val channels = shape[1].toInt()
        val sourceFrames = shape[2].toInt()
        val frames = end - start
        val result = FloatArray(channels * frames)
        for (channel in 0 until channels) values.copyInto(result, channel * frames, channel * sourceFrames + start, channel * sourceFrames + end)
        return FloatTensorData(result, longArrayOf(1, channels.toLong(), frames.toLong()))
    }

    private fun concat(vararg parts: FloatTensorData): FloatTensorData {
        val channels = parts[0].shape[1].toInt()
        val frames = parts.sumOf { it.shape[2].toInt() }
        val values = FloatArray(channels * frames)
        var offset = 0
        for (part in parts) {
            val count = part.shape[2].toInt()
            for (channel in 0 until channels) part.values.copyInto(values, channel * frames + offset, channel * count, (channel + 1) * count)
            offset += count
        }
        return FloatTensorData(values, longArrayOf(1, channels.toLong(), frames.toLong()))
    }

    companion object {
        const val VOCODER = "v5-vocoder-subpixel.onnx"
    }
}
