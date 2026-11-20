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
) : BackendProvider {
    override val id = "gptsovits"
    override val capabilities = BackendCapabilities(
        listOf("zh", "ja", "en"), SynthesisOptions.Default.toRequestOptions(500).keys,
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
        OrtEnvironment.getEnvironment() // Register ORT's logger before initializing the provider.
        OrtSession.SessionOptions().use { it.addWebGPU(emptyMap()) }
        true
    } catch (error: OrtException) {
        Log.i("VoiceBoxTiming", "WebGPU initialization failed: ${error.message}")
        false
    }

    override fun createRuntime(executionBackend: ExecutionBackend): BackendRuntime =
        OrtSessionRuntime(
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
                qnnRuntimeDirectory = {
                    deviceAcceleration.runtimeDirectory.takeIf { accelerationEnabled() }
                },
                onNpuUnavailable = deviceAcceleration::modelUnavailable,
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
