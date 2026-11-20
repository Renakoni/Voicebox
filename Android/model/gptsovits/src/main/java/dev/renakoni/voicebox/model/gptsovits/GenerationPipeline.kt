package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.BackendRuntimeCallbacks
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GeneratedAudioFile
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.WavFileWriter

class GenerationPipeline(
    private val backend: PreparedTtsBackend,
    private val inputPreparer: TtsInputPreparer,
    private val outputDirectory: java.io.File,
    private val traceLogger: InferenceTraceLogger = InferenceTraceLogger.None,
    private val executionBackend: ExecutionBackend? = null,
    private val runtimeLabel: () -> String? = { null },
    private val progressCallbacks: BackendRuntimeCallbacks? = null,
) {
    fun generate(request: GenerationRequest): GeneratedAudioFile {
        val timer = InferenceTimer(traceLogger)
        return timer.measure("generation_total_ms") {
            validate(request)
            val steps = request.maxDecoderSteps
            val options = request.synthesisOptions
            request.context.checkCancelled()
            executionBackend?.let { timer.event(InferenceTraceEvent.Backend(it)) }
            val preparedInput = timer.measure("prepare_total_ms") {
                inputPreparer.prepare(request)
            }
            request.context.checkCancelled()
            progressCallbacks?.onStage(
                GenerationStage.RUNNING_INFERENCE,
                "running_inference",
                GenerationProgress.T2S_ENCODER,
                "T2S encoder",
            )
            val result = timer.measure("backend_total_ms") {
                backend.generatePrepared(preparedInput, steps, options)
            }
            request.context.checkCancelled()
            runtimeLabel()?.let { label ->
                timer.event(InferenceTraceEvent.Runtime(InferenceRuntimeLabel.entries.first { it.wireValue == label }))
            }
            timer.event(InferenceTraceEvent.TensorCount(InferenceTensorMetric.AUDIO_SAMPLES, result.audio.size.toLong()))
            timer.event(InferenceTraceEvent.TensorShape(InferenceTensorMetric.AUDIO_SHAPE, result.shape))
            timer.traceFloatTensor(InferenceTensorMetric.AUDIO_HASH, result.audio)
            val outputFile = java.io.File(
                outputDirectory,
                "${request.characterModel.id}-${request.context.requestId}.wav",
            )
            progressCallbacks?.onStage(
                GenerationStage.WRITING_OUTPUT,
                "writing_output",
                GenerationProgress.WRITING_OUTPUT,
                "Writing output",
            )
            try {
                timer.measure("write_wav_ms") {
                    WavFileWriter.writeMonoPcm16(outputFile, result.audio, result.sampleRate)
                }
                request.context.checkCancelled()
                progressCallbacks?.onStage(
                    GenerationStage.WRITING_OUTPUT,
                    "writing_output",
                    GenerationProgress.OUTPUT_READY,
                    "Output ready",
                )
                request.context.checkCancelled()
                GeneratedAudioFile(
                    file = outputFile,
                    audioSamples = result.audio.size,
                    sampleRate = result.sampleRate,
                )
            } catch (error: Throwable) {
                outputFile.delete()
                throw error
            }
        }
    }

    private fun validate(request: GenerationRequest) {
        require(request.synthesisText.isNotBlank()) { "Synthesis text is required." }
        require(request.referenceAudioPath.isNotBlank()) { "Reference audio path is required." }
        require(request.referenceText.isNotBlank()) { "Reference text is required." }
        require(request.language.isNotBlank()) { "Language is required." }
    }

}
