package dev.rinstel.genie_tts.inference

class OrtSessionRuntime(
    private val modelRepository: ModelAssetRepository,
    private val runtimeRepository: RuntimeAssetRepository,
    private val backendEngine: OrtCpuBackend = OrtCpuBackend(
        backend = ExecutionBackend.CPU,
        configureSessionOptions = OrtCpuBackend::configureCpuSessionOptions,
        configureRoleSessionOptions = OrtCpuBackend::configureCpuSessionOptionsForRole,
    ),
    private val traceLogger: InferenceTraceLogger = InferenceTraceLogger.None,
    private val featureExtractorFactory: (OrtCpuBackend) -> OrtRuntimeFeatureExtractor,
) : BackendRuntime {
    override val backend: ExecutionBackend = backendEngine.backend
    override val runtimeLabel: String
        get() = if (backendEngine.usesNpu) "ORT CPU + WebGPU + QNN" else when (backendEngine.backend) {
            ExecutionBackend.CPU -> "ORT CPU EP"
            ExecutionBackend.WEBGPU -> "ORT CPU + WebGPU"
        }

    private var runtimeFeatureExtractor: OrtRuntimeFeatureExtractor? = null
    private var initializedModelDirectory: String? = null
    private var runtimePrepared = false

    override fun generate(
        request: GenerationRequest,
        callbacks: BackendRuntimeCallbacks,
    ): GeneratedAudioFile {
        val featureExtractor = prepareResources(request, callbacks)

        val result = try {
            backendEngine.runtimeCallbacks = callbacks
            GenerationPipeline(
                backend = backendEngine,
                inputPreparer = featureExtractor,
                outputDirectory = runtimeRepository.outputRoot(),
                traceLogger = traceLogger,
                executionBackend = backendEngine.backend,
                runtimeLabel = runtimeLabel,
                progressCallbacks = callbacks,
            ).generate(request)
        } finally {
            backendEngine.runtimeCallbacks = null
        }

        return result
    }

    override fun warmup(request: GenerationRequest) {
        val timer = InferenceTimer(traceLogger)
        val featureExtractor = prepareResources(request)
        timer.measure("prepare_total_ms") {
            featureExtractor.warmup(request)
        }
        timer.measure("t2s_session_preload_ms") {
            backendEngine.preloadT2SSessions()
        }
        // Move the largest session-construction cost into background warmup so
        // the first user request can start with a hot VITS session.
        timer.measure("vocoder_session_ms") {
            backendEngine.preloadVocoderSession()
        }
    }

    private fun prepareResources(request: GenerationRequest, callbacks: BackendRuntimeCallbacks? = null): OrtRuntimeFeatureExtractor {
        val timer = InferenceTimer(traceLogger)
        val model = request.characterModel
        request.context.checkCancelled()
        if (!backendEngine.isInitialized() || initializedModelDirectory != model.relativeModelDirectory) {
            callbacks?.onStage(GenerationStage.INSTALLING_MODEL, "installing_model", null, "Preparing model")
            timer.measure("model_install_ms") {
                modelRepository.installToPrivateStorage(model.modelFiles) { done, total ->
                    request.context.checkCancelled()
                    callbacks?.onStage(GenerationStage.INSTALLING_MODEL, "installing_model", null, "Unpacking weights $done/$total")
                }
            }
            callbacks?.onStage(GenerationStage.INITIALIZING_BACKEND, "initializing_backend", null, "Initializing backend")
            timer.measure("backend_initialization_ms") {
                backendEngine.initialize(modelRepository.modelRootDirectory(), model)
            }
            initializedModelDirectory = model.relativeModelDirectory
        }
        if (!runtimePrepared) {
            val inspection = timer.measure("runtime_inspection_ms") { runtimeRepository.inspect() }
            require(inspection.isComplete) { "Missing runtime files: ${inspection.missingFiles.joinToString(", ")}" }
            timer.measure("runtime_weights_ms") {
                runtimeRepository.prepareRuntimeWeights { done, total ->
                    request.context.checkCancelled()
                    callbacks?.onStage(GenerationStage.PREPARING_FEATURES, "preparing_runtime", null, "Unpacking weights $done/$total")
                }
            }
            runtimePrepared = true
        }
        // UI warmup may target a different voice than the next API request.
        // Compile only for an actual request, so background warmup cannot hold
        // the generation queue while preparing an unused voice's NPU contexts.
        if (callbacks != null) backendEngine.prepareAcceleration { done, total ->
            request.context.checkCancelled()
            callbacks.onStage(GenerationStage.INITIALIZING_BACKEND, "initializing_backend", null, "NPU $done/$total")
        }
        request.context.checkCancelled()
        callbacks?.onStage(GenerationStage.PREPARING_FEATURES, "preparing_features", null, "Preparing features")
        return runtimeFeatureExtractor ?: featureExtractorFactory(backendEngine).also { runtimeFeatureExtractor = it }
    }

    override fun close() {
        runtimeFeatureExtractor?.close()
        runtimeFeatureExtractor = null
        backendEngine.close()
        initializedModelDirectory = null
        runtimePrepared = false
    }

    override fun trimMemory() {
        runtimeFeatureExtractor?.releaseLoadedSessions()
        backendEngine.setRetainT2SSessionsAfterGeneration(false)
        backendEngine.releaseLoadedSessions()
    }
}
