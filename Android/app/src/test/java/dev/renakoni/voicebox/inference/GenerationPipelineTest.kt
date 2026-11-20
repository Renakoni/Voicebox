package dev.renakoni.voicebox.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class GenerationPipelineTest {
    @Test
    fun cancellationInOutputReadyCallbackDeletesWrittenAudio() {
        val outputDirectory = java.nio.file.Files.createTempDirectory("voicebox-test-").toFile()
        val request = GenerationRequest(
            characterModel = CharacterModelCatalog.v2ProPlusFromCharacterIds(listOf("mansui")).first(),
            language = "zh", synthesisText = "test",
            referenceAudioPath = "/tmp/ref.wav", referenceText = "reference",
        )
        val tensor = FloatTensorData(floatArrayOf(0f), longArrayOf(1L, 1L))
        val sequence = LongTensorData(longArrayOf(1L), longArrayOf(1L, 1L))
        val pipeline = GenerationPipeline(
            backend = object : PreparedTtsBackend {
                override fun generatePrepared(input: TtsPreparedInput, maxDecoderSteps: Int, options: SynthesisOptions) =
                    TtsGenerationResult(floatArrayOf(0.25f), longArrayOf(1L, 1L, 1L))
            },
            inputPreparer = object : TtsInputPreparer {
                override fun prepare(request: GenerationRequest) = TtsPreparedInput(
                    refSeq = sequence, textSeq = sequence, refBert = tensor, textBert = tensor,
                    sslContent = tensor, globalEmbedding = tensor, advancedGlobalEmbedding = tensor,
                )
            },
            outputDirectory = outputDirectory,
            progressCallbacks = object : BackendRuntimeCallbacks {
                override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) {
                    if (progressPercent == GenerationProgress.OUTPUT_READY) {
                        assertEquals(1, outputDirectory.listFiles()!!.size)
                        request.context.cancel("TIMEOUT")
                        request.context.checkCancelled()
                    }
                }
            },
        )
        try {
            assertThrows(GenerationCancelledException::class.java) { pipeline.generate(request) }
            assertTrue(outputDirectory.listFiles()!!.isEmpty())
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun writesGeneratedAudioToOutputFile() {
        val outputDirectory = java.nio.file.Files.createTempDirectory("voicebox-test-").toFile()
        var capturedMaxDecoderSteps = -1
        var capturedOptions: SynthesisOptions? = null
        var runtimeLabel = "ORT CPU + WebGPU"
        val recordedRuntimes = mutableListOf<InferenceRuntimeLabel>()
        val request = GenerationRequest(
            characterModel = CharacterModelCatalog.v2ProPlusFromCharacterIds(listOf("mansui")).first(),
            language = "zh",
            synthesisText = "test",
            referenceAudioPath = "/tmp/ref.wav",
            referenceText = "reference",
            maxDecoderSteps = 123,
            synthesisOptions = SynthesisOptions(speed = 1.4, topP = 0.8f),
        )
        val backend = object : PreparedTtsBackend {
            override fun generatePrepared(input: TtsPreparedInput, maxDecoderSteps: Int, options: SynthesisOptions): TtsGenerationResult {
                capturedMaxDecoderSteps = maxDecoderSteps
                capturedOptions = options
                runtimeLabel = "ORT CPU + WebGPU + QNN"
                return TtsGenerationResult(floatArrayOf(0f, 0.25f, -0.25f), longArrayOf(1L, 1L, 3L), sampleRate = 48000)
            }
        }
        val inputPreparer = object : TtsInputPreparer {
            override fun prepare(request: GenerationRequest): TtsPreparedInput =
                TtsPreparedInput(
                    refSeq = LongTensorData(longArrayOf(1L), longArrayOf(1L, 1L)),
                    textSeq = LongTensorData(longArrayOf(2L), longArrayOf(1L, 1L)),
                    refBert = FloatTensorData(FloatArray(1024), longArrayOf(1L, 1024L)),
                    textBert = FloatTensorData(FloatArray(1024), longArrayOf(1L, 1024L)),
                    sslContent = FloatTensorData(floatArrayOf(0f), longArrayOf(1L, 1L, 1L)),
                    globalEmbedding = FloatTensorData(floatArrayOf(0f), longArrayOf(1L, 1L)),
                    advancedGlobalEmbedding = FloatTensorData(floatArrayOf(0f), longArrayOf(1L, 1L)),
                )
        }

        val output = GenerationPipeline(
            backend = backend,
            inputPreparer = inputPreparer,
            outputDirectory = outputDirectory,
            executionBackend = ExecutionBackend.WEBGPU,
            runtimeLabel = { runtimeLabel },
            traceLogger = object : InferenceTraceLogger {
                override fun stage(name: String, elapsedMs: Long) = Unit
                override fun event(event: InferenceTraceEvent) {
                    if (event is InferenceTraceEvent.Runtime) recordedRuntimes += event.value
                }
            },
        ).generate(request)

        assertTrue(output.file.exists())
        assertEquals(File(outputDirectory, output.file.name).absolutePath, output.file.absolutePath)
        assertEquals(3, output.audioSamples)
        assertEquals(48000, output.sampleRate)
        assertEquals(48000, WavAudioReader.readMonoPcm(output.file).sampleRate)
        assertEquals(123, capturedMaxDecoderSteps)
        assertEquals(request.synthesisOptions, capturedOptions)
        assertEquals(listOf(InferenceRuntimeLabel.QNN), recordedRuntimes)
        outputDirectory.deleteRecursively()
    }

}
