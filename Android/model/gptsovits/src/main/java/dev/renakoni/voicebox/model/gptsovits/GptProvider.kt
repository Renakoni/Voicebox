package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.BackendProvider
import dev.renakoni.voicebox.bridge.BackendRuntime
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend

import dev.renakoni.voicebox.bridge.BackendCapabilities
import dev.renakoni.voicebox.bridge.VoiceImport
import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import dev.renakoni.voicebox.core.openBackendAsset
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import java.io.File

class GptProvider(
    private val context: Context,
    private val deviceAcceleration: DeviceAcceleration,
    private val t2sThreads: () -> Int,
    private val vocoderThreads: () -> Int,
    private val accelerationEnabled: () -> Boolean,
) : BackendProvider, dev.renakoni.voicebox.bridge.VoiceLibrary by GptLibrary(context, deviceAcceleration) {
    override val id = "gptsovits"
    override val capabilities = BackendCapabilities(
        listOf("zh", "ja", "en"),
        setOf(VoiceImport.CHECKPOINT_PAIR, VoiceImport.RUNTIME_ARCHIVE),
    )
    override fun languageError(language: String, text: String) = LanguageNormalizer.scriptError(language, text)?.key
    override val supportsWarmup = true
    private val modelRepository = GptModelRepository(context)
    private val runtimeRepository = RuntimeAssetRepository(context)
    private val traceLogger = LogcatInferenceTraceLogger()

    override fun discoverCharacterModels() = modelRepository.discoverCharacterModels()
    override fun inspect(model: CharacterModel) = modelRepository.inspect(model.modelFiles)
    override fun findReferenceExample(model: CharacterModel) = modelRepository.findReferenceExample(model)
    override fun supportsAuxiliaryReferences(model: CharacterModel) = model.maxAuxiliaryReferences > 0 &&
        File(modelRepository.modelRootDirectory(), "${model.relativeModelDirectory}/prompt_encoder_multi_fp32.onnx").isFile

    override fun validateOptions(options: Map<String, String>): Map<String, String> {
        return SynthesisOptions.validateRequestOptions(options)
    }

    override fun executionModes(): Set<ExecutionBackend> {
        val webGpu = OrtProvider.WEBGPU in OrtEnvironment.getAvailableProviders() && canInitializeWebGpu()
        deviceAcceleration.detect(webGpu)
        return if (webGpu) ExecutionBackend.entries.toSet() else setOf(ExecutionBackend.CPU)
    }

    private fun canInitializeWebGpu(): Boolean = try {
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.addWebGPU(emptyMap())
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            val graph = context.openBackendAsset("device-acceleration/probe.onnx").use { it.readBytes() }
            environment.createSession(graph, options).use { session ->
                OnnxTensor.createTensor(environment, arrayOf(floatArrayOf(1f, 2f, 3f, 4f))).use { input ->
                    session.run(mapOf("x" to input)).use { result ->
                        val output = (result[0] as OnnxTensor).floatBuffer
                        (0 until 4).all { kotlin.math.abs(output[it] - ((it + 1) * .5f + .25f)) < .01f }
                    }
                }
            }
        }
    } catch (error: OrtException) {
        Log.i("VoiceBoxTiming", "WebGPU initialization failed: ${error.message}")
        false
    }

    override fun createRuntime(executionBackend: ExecutionBackend): BackendRuntime {
        if (executionBackend == ExecutionBackend.WEBGPU && accelerationEnabled()) deviceAcceleration.detect(true)
        return OrtSessionRuntime(
            modelRepository = modelRepository,
            runtimeRepository = runtimeRepository,
            backendEngine = OrtCpuBackend(
                backend = executionBackend,
                configureSessionOptions = OrtCpuBackend::configureCpuSessionOptions,
                configureRoleSessionOptions = { options, role ->
                    OrtCpuBackend.configureCpuSessionOptionsForRole(
                        options = options,
                        role = role,
                        t2sThreadLimit = t2sThreads(),
                        vocoderThreadLimit = vocoderThreads(),
                    )
                },
                traceLogger = traceLogger,
                useNativeDecoder = true,
                qnnRuntimeDirectory = deviceAcceleration.runtimeDirectory,
            ),
            featureExtractorFactory = { backend ->
                OrtRuntimeFeatureExtractor(
                    context = context,
                    runtimeAssets = runtimeRepository,
                    backend = backend,
                    traceLogger = traceLogger,
                )
            },
            traceLogger = traceLogger,
        )
    }
}
