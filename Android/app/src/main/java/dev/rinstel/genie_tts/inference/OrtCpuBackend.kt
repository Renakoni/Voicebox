package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.lang.reflect.Array
import java.nio.FloatBuffer
import java.nio.LongBuffer

class OrtCpuBackend(
    val backend: ExecutionBackend = ExecutionBackend.CPU,
    private val configureSessionOptions: (OrtSession.SessionOptions) -> Unit = {},
    private val traceLogger: InferenceTraceLogger = InferenceTraceLogger.None,
    private val configureRoleSessionOptions: ((OrtSession.SessionOptions, InferenceModelRole) -> Unit)? = null,
    // Keep autoregressive execution and shared KV buffers in native code.
    private val useNativeDecoder: Boolean = false,
    private val qnnRuntimeDirectory: () -> File? = { null },
    private val qnnPerformance: String = "balanced",
    private val onNpuUnavailable: () -> Unit = {},
) : AutoCloseable, PreparedTtsBackend {

    private val environment: OrtEnvironment by lazy(LazyThreadSafetyMode.NONE) {
        OrtEnvironment.getEnvironment()
    }
    private val sessions: MutableMap<String, OrtSession> = linkedMapOf()
    private val sessionFiles: MutableMap<String, File> = linkedMapOf()
    private var modelFiles: RequiredModelFiles? = null
    private var multiReferenceCapabilityResolved = false
    private var cachedMultiReferenceCapability: MultiReferenceCapability? = null
    private var v5Acoustic: V5AcousticRuntime? = null
    private var v5DiT: V5DiTRuntime? = null
    private var v2Htp: V2HtpVocoder? = null
    val usesNpu: Boolean get() = v5DiT?.lastUsedNpu == true || v2Htp?.lastUsedNpu == true

    @Volatile
    private var retainT2SSessionsAfterGeneration = false

    @Volatile
    private var retainVocoderSessionAfterGeneration = false

    @Volatile
    private var memoryHeadroomProvider: () -> Boolean = { true }

    @Volatile
    internal var runtimeCallbacks: BackendRuntimeCallbacks? = null

    internal fun reportProgress(
        stage: GenerationStage,
        message: String,
        progressPercent: Int?,
        progressLabel: String?,
    ) {
        GenerationContext.checkCurrent()
        runtimeCallbacks?.onStage(stage, message, progressPercent, progressLabel)
    }

    // Initialization only validates and indexes model files. Heavy ORT
    // sessions are opened on demand so runtime feature models do not overlap
    // with the T2S/vocoder working set.
    fun isInitialized(): Boolean = sessionFiles.isNotEmpty()

    fun hasPromptEncoder(): Boolean =
        sessionFiles.keys.any { it.contains("prompt_encoder", ignoreCase = true) }

    fun multiReferenceCapability(): MultiReferenceCapability? {
        if (multiReferenceCapabilityResolved) return cachedMultiReferenceCapability
        val resolved = when {
            !hasPromptEncoder() -> null
            MULTI_REFERENCE_PROMPT_ENCODER !in sessionFiles ->
                MultiReferenceCapability(supported = false)
            else -> runCatching {
                MultiReferenceCapability.fromSession(session(MULTI_REFERENCE_PROMPT_ENCODER))
            }.getOrDefault(MultiReferenceCapability(supported = false))
        }
        multiReferenceCapabilityResolved = true
        cachedMultiReferenceCapability = resolved
        return resolved
    }

    data class CpuThreadPlan(
        val intraOpThreads: Int,
        val interOpThreads: Int,
    )

    fun applySessionOptions(
        options: OrtSession.SessionOptions,
        role: InferenceModelRole,
    ) {
        configureRoleSessionOptions?.invoke(options, role) ?: configureSessionOptions(options)
    }

    fun initialize(modelRoot: File, characterModel: CharacterModel) {
        close()

        val modelDirectory = File(modelRoot, characterModel.relativeModelDirectory)
        val sessionModelNames = characterModel.modelFiles.sessionModelNames(
            modelDirectory,
        )
        val optimized = OptimizedDecoderAssets.resolve(modelDirectory,
            sharedCacheAllowed = useNativeDecoder && NativeDecoderLoop.isAvailable())
        val resolvedFiles = linkedMapOf<String, File>()
        for (modelName in sessionModelNames) {
            val modelFile = optimized[modelName] ?: File(modelDirectory, modelName)
            require(modelFile.isFile) { "Model file not found: ${modelFile.absolutePath}" }
            resolvedFiles[modelName] = modelFile
        }
        if ("v5-dit.onnx" in sessionModelNames) {
            val shared = File(modelRoot.parentFile, "RuntimeAssets/v5-vocoder")
            require(File(shared, V5AcousticRuntime.VOCODER).isFile && File(shared, "v5_vocoder_fp32.bin").isFile) {
                "缺少 V5 公共声码器资源：${shared.absolutePath}"
            }
            resolvedFiles[V5AcousticRuntime.VOCODER] = File(shared, V5AcousticRuntime.VOCODER)
            if (backend == ExecutionBackend.WEBGPU &&
                listOf(V5DiTRuntime.GRAPH, V5DiTRuntime.TIME).all { File(modelDirectory, it).isFile }) {
                for (name in listOf(V5DiTRuntime.GRAPH, V5DiTRuntime.TIME)) resolvedFiles[name] = File(modelDirectory, name)
                for (name in V5DiTRuntime.SPLIT_GRAPHS) {
                    File(modelDirectory, name).takeIf { it.isFile }?.let { resolvedFiles[name] = it }
                }
                v5DiT = V5DiTRuntime(environment, ::session, { name -> closeSessions { it == name } },
                    modelDirectory, qnnRuntimeDirectory, qnnPerformance, traceLogger, onNpuUnavailable)
            }
            v5Acoustic = V5AcousticRuntime(environment, ::session, traceLogger, v5DiT,
                accelerationProgress = { done, total, compiling ->
                    reportProgress(GenerationStage.INITIALIZING_BACKEND, "initializing_backend", null,
                        "NPU ${if (compiling) "compiling" else "loading"} $done/$total")
                }) { done, total ->
                reportProgress(GenerationStage.RUNNING_INFERENCE, "running_inference", null, "CFM $done/$total")
            }
        } else if (backend == ExecutionBackend.WEBGPU) {
            SplitVocoderAssets.validate(modelDirectory).forEach { resolvedFiles[it] = File(modelDirectory, it) }
            if (V2HtpVocoder.GRAPHS.all { File(modelDirectory, it).isFile }) {
                for (name in listOf(V2HtpVocoder.STEM, V2HtpVocoder.HEAD)) resolvedFiles[name] = File(modelDirectory, name)
                v2Htp = V2HtpVocoder(environment, ::session, modelDirectory, qnnRuntimeDirectory,
                    qnnPerformance, traceLogger, onNpuUnavailable)
            }
        }
        sessionFiles.putAll(resolvedFiles)
        modelFiles = characterModel.modelFiles

        // Do not create any sessions here. RoBERTa, HuBERT, speaker encoder,
        // and the character graphs are loaded in disjoint inference phases.
    }

    internal fun prepareAcceleration(progress: (Int, Int, Boolean) -> Unit) {
        v2Htp?.prepare(progress)
    }

    private fun createSession(
        modelFile: File,
        role: InferenceModelRole,
    ): OrtSession {
        val options = OrtSession.SessionOptions()
        return try {
            applySessionOptions(options, role)
            // The custom ORT runtime stores exactly representable decoder weights
            // in half precision, with FP32 arithmetic. Stock ORT ignores this key.
            if (modelFile.name == "mobile-decoder-shared.onnx") {
                options.addConfigEntry("session.genie_fp16_weight_storage", "1")
            }
            if (backend == ExecutionBackend.WEBGPU && usesWebGpu(modelFile.name)) {
                options.addWebGPU(emptyMap())
            }
            // Prompt weights are already materialized as the model's normal
            // FP32 external-data file during model import. Let ORT read that
            // file directly so the app heap never holds a second Tensor set.
            environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    override fun generatePrepared(input: TtsPreparedInput, maxDecoderSteps: Int): TtsGenerationResult {
        GenerationContext.checkCurrent()
        require(isInitialized()) { "CPU backend is not initialized." }

        val timer = InferenceTimer(traceLogger)
        val ownedTensors = mutableListOf<OnnxTensor>()
        var encoderResult: OrtSession.Result? = null
        var previousDecoderResult: OrtSession.Result? = null
        var generationSucceeded = false

        try {
            val t2sInputs = timer.measure("t2s_input_tensors_ms") {
                arrayOf(
                    createLongTensor(input.refSeq).also(ownedTensors::add),
                    createLongTensor(input.textSeq).also(ownedTensors::add),
                    createFloatTensor(input.refBert).also(ownedTensors::add),
                    createFloatTensor(input.textBert).also(ownedTensors::add),
                    createFloatTensor(input.sslContent).also(ownedTensors::add),
                )
            }
            val refSeq = t2sInputs[0]
            val textSeq = t2sInputs[1]
            val refBert = t2sInputs[2]
            val textBert = t2sInputs[3]
            val sslContent = t2sInputs[4]

            encoderResult = timer.measure("t2s_encoder_ms") {
                session(T2S_ENCODER).runCancellable(
                    mapOf(
                        "ref_seq" to refSeq,
                        "text_seq" to textSeq,
                        "ref_bert" to refBert,
                        "text_bert" to textBert,
                        "ssl_content" to sslContent,
                    ),
                )
            }

            val currentEncoderResult = requireNotNull(encoderResult)
            val promptCodes = if (v5Acoustic != null) longTensorValues(currentEncoderResult.get(1) as OnnxTensor) else null
            reportProgress(
                GenerationStage.RUNNING_INFERENCE,
                "running_inference",
                GenerationProgress.FIRST_DECODER,
                "First decoder",
            )
            previousDecoderResult = timer.measure("t2s_first_decoder_ms") {
                session(T2S_FIRST_STAGE_DECODER).runCancellable(
                    mapOf(
                        "x" to tensorLike(currentEncoderResult, 0),
                        "prompts" to tensorLike(currentEncoderResult, 1),
                    ),
                )
            }
            encoderResult.close()
            encoderResult = null

            val stageSession = session(T2S_STAGE_DECODER)
            val stageInputNames = stageSession.inputNames.toList()
            val stageOutputNames = stageSession.outputNames.toList()
            val decoderProgress = decoderProgressCallback(maxDecoderSteps)
            val nativeDecoderAvailable = useNativeDecoder &&
                NativeDecoderLoop.isAvailable()
            val predSemantic = if (nativeDecoderAvailable) {
                val firstDecoderResult = requireNotNull(previousDecoderResult)

                val yTensor = firstDecoderResult.get(0) as OnnxTensor
                val yData = LongArray(yTensor.info.numElements.toInt())
                yTensor.longBuffer.get(yData)
                val yShape = yTensor.info.shape

                val yEmbTensor = firstDecoderResult.get(1) as OnnxTensor
                val yEmbData = FloatArray(yEmbTensor.info.numElements.toInt())
                yEmbTensor.floatBuffer.get(yEmbData)
                val yEmbShape = yEmbTensor.info.shape

                val numKvTensors = (firstDecoderResult.size() - 2) / 2 * 2
                require(numKvTensors > 0) { "Decoder did not return KV tensors." }
                val kvTensor = firstDecoderResult.get(2) as OnnxTensor
                val kvShape = kvTensor.info.shape
                val kvElements = kvTensor.info.numElements.toInt()
                val kvData = FloatArray(kvElements * numKvTensors)
                for (index in 0 until numKvTensors) {
                    val tensor = firstDecoderResult.get(2 + index) as OnnxTensor
                    tensor.floatBuffer.get(kvData, index * kvElements, kvElements)
                }
                firstDecoderResult.close()
                previousDecoderResult = null

                val outputMapping = IntArray(stageInputNames.size) { index ->
                    if (stageInputNames[index] == "cache_length") -1 else if (index < 2) index else index + 1
                }
                val nativeProgress = NativeDecoderLoop.DecoderProgressCallback { generated, maximum ->
                    decoderProgress(generated, maximum)
                }
                val semantic: LongArray = timer.measure("decoder_loop_ms") {
                    val textTokenCount = input.textSeq.values.size
                    var retryCount = 0
                    var bestSemantic: LongArray? = null
                    while (true) {
                        GenerationContext.checkCurrent()
                        val attemptStart = System.nanoTime()
                        val candidate = try { NativeDecoderLoop.runDecoderLoop(
                            session = stageSession,
                            inputNames = stageInputNames,
                            outputNames = stageOutputNames,
                            initialY = yData,
                            yShape = yShape,
                            initialYEmb = yEmbData,
                            yEmbShape = yEmbShape,
                            initialKv = kvData,
                            kvShape = kvShape,
                            numKvTensors = numKvTensors,
                            outputMapping = outputMapping,
                            maxSteps = maxDecoderSteps,
                            progressCallback = if (retryCount == 0) nativeProgress else null,
                        ) } catch (error: Exception) {
                            timer.event(InferenceTraceEvent.DecoderAttempt(
                                retryCount + 1, null, (System.nanoTime() - attemptStart) / 1_000_000L,
                                if (error is GenerationCancelledException) "cancelled" else "failed",
                            ))
                            throw error
                        }
                        timer.event(InferenceTraceEvent.DecoderAttempt(
                            retryCount + 1, candidate.size, (System.nanoTime() - attemptStart) / 1_000_000L,
                        ))
                        if (candidate.size > (bestSemantic?.size ?: -1)) {
                            bestSemantic = candidate
                        }
                        if (!SemanticTokenStability.shouldRetry(
                                candidateTokenCount = candidate.size,
                                textTokenCount = textTokenCount,
                                retryCount = retryCount,
                            )) {
                            break
                        }
                        retryCount += 1
                    }
                    requireNotNull(bestSemantic)
                }
                val finalSemantic = SemanticTokenStability.requireNonEmpty(semantic)
                timer.measure("semantic_tensor_ms") {
                    OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(finalSemantic),
                        longArrayOf(1L, 1L, finalSemantic.size.toLong()),
                    ).also(ownedTensors::add)
                }
            } else {
                val decoderInputs = mutableListOf<OnnxTensorLike>()
                val feed = mutableMapOf<String, OnnxTensorLike>()
                var generatedSteps = 0
                var presentStartIndex = 2

                val attemptStart = System.nanoTime()
                run {
                    timer.measure("decoder_loop_ms") {
                        while (generatedSteps < maxDecoderSteps) {
                            val previous = requireNotNull(previousDecoderResult)
                            decoderInputs.clear()
                            decoderInputs.add(tensorLike(previous, 0))
                            decoderInputs.add(tensorLike(previous, 1))
                            for (index in presentStartIndex until previous.size()) {
                                decoderInputs.add(tensorLike(previous, index))
                            }

                            feed.clear()
                            for (i in stageInputNames.indices) {
                                feed[stageInputNames[i]] = decoderInputs[i]
                            }

                            val stageResult = stageSession.runCancellable(feed)
                            previous.close()
                            previousDecoderResult = stageResult
                            generatedSteps += 1
                            presentStartIndex = 3
                            decoderProgress(generatedSteps, maxDecoderSteps)

                            if (booleanValue(stageResult.get(2))) {
                                break
                            }
                        }
                    }
                }

                val decoderResult = requireNotNull(previousDecoderResult)
                val semanticTensor = timer.measure("semantic_tensor_ms") {
                    semanticTensorFromDecoderOutput(decoderResult, generatedSteps)
                }
                timer.event(InferenceTraceEvent.DecoderAttempt(
                    1, semanticTensor.info.numElements.toInt(), (System.nanoTime() - attemptStart) / 1_000_000L,
                ))
                decoderResult.close()
                previousDecoderResult = null
                semanticTensor.also(ownedTensors::add)
            }

            reportProgress(
                GenerationStage.RUNNING_INFERENCE,
                "running_inference",
                GenerationProgress.DECODER_END,
                "Decoder complete",
            )

            // Decoder results are copied into predSemantic above. Release the
            // encoder before VITS; a memory-approved hot path keeps the
            // reusable T2S decoders and VITS session for the next sentence.
            if (!retainT2SSessionsAfterGeneration) {
                closeSessions { roleForSession(it) == InferenceModelRole.T2S }
            } else {
                releaseLoadedSessionsKeepingReusableT2SAndVocoder()
            }

            v5Acoustic?.let { acoustic ->
                return timer.measure("vocoder_ms") {
                    acoustic.generate(input, predSemantic, requireNotNull(promptCodes))
                }.also { generationSucceeded = true }
            }

            val vocoderInputs = timer.measure("vocoder_input_tensors_ms") {
                timer.event(InferenceTraceEvent.TensorCount(InferenceTensorMetric.SEMANTIC_TOKENS, predSemantic.info.numElements))
                timer.event(InferenceTraceEvent.TensorShape(InferenceTensorMetric.SEMANTIC_SHAPE, predSemantic.info.shape))
                if (timer.tensorDiagnostics) timer.traceLongTensor(InferenceTensorMetric.SEMANTIC_HASH, longTensorValues(predSemantic))
                if (hasPromptEncoder()) {
                    val ge = createFloatTensor(input.globalEmbedding).also(ownedTensors::add)
                    val geAdvanced = createFloatTensor(input.advancedGlobalEmbedding).also(ownedTensors::add)
                    mapOf(
                        "text_seq" to textSeq,
                        "pred_semantic" to predSemantic,
                        "ge" to ge,
                        "ge_advanced" to geAdvanced,
                    )
                } else {
                    val refAudio = createFloatTensor(
                        requireNotNull(input.refAudio32k) { "V2 vocoder requires refAudio32k in TtsPreparedInput." }
                    ).also(ownedTensors::add)
                    mapOf(
                        "text_seq" to textSeq,
                        "pred_semantic" to predSemantic,
                        "ref_audio" to refAudio,
                    )
                }
            }

            // ORT exposes no callback inside one VITS graph execution. Use an
            // indeterminate bar here instead of claiming a false percentage.
            reportProgress(
                GenerationStage.RUNNING_INFERENCE,
                "running_inference",
                null,
                "Vocoder",
            )
            fun copyAudio(result: OrtSession.Result): TtsGenerationResult = result.use {
                timer.measure("vocoder_output_copy_ms") {
                    val audioTensor = result.get(0) as OnnxTensor
                    val audio = FloatArray(audioTensor.info.numElements.toInt())
                    audioTensor.floatBuffer.get(audio)
                    TtsGenerationResult(audio = audio, shape = audioTensor.info.shape)
                }
            }
            val generationResult = timer.measure("vocoder_ms") {
                if (backend == ExecutionBackend.WEBGPU) {
                    timer.measure("vits_encoder_ms") {
                        session(SplitVocoderAssets.ENCODER).runCancellable(vocoderInputs)
                    }.use { encoded ->
                        timer.measure("hifigan_ms") {
                            val latent = encoded.get(0) as OnnxTensor
                            val ge = requireNotNull(vocoderInputs["ge"])
                            v2Htp?.generate(latent, ge) ?: copyAudio(session(SplitVocoderAssets.HIFIGAN)
                                .runCancellable(mapOf(SplitVocoderAssets.BOUNDARY to latent, "ge" to ge)))
                        }
                    }
                } else copyAudio(session(VITS).runCancellable(vocoderInputs))
            }
            reportProgress(
                GenerationStage.RUNNING_INFERENCE,
                "running_inference",
                GenerationProgress.VOCODER_COMPLETE,
                "Vocoder complete",
            )
            return generationResult.also { generationSucceeded = true }
        } finally {
            encoderResult?.close()
            previousDecoderResult?.close()
            ownedTensors.forEach(OnnxTensor::close)
            // The audio has already been copied out of ORT. Keep the large
            // synthesis sessions only when the post-run memory watermark still
            // permits it; otherwise return to the bounded cold-session path.
            timer.measure("session_cleanup_ms") {
                if (retainT2SSessionsAfterGeneration && generationSucceeded) {
                    if (retainVocoderSessionAfterGeneration && !memoryHeadroomProvider()) {
                        retainVocoderSessionAfterGeneration = false
                    }
                    releaseLoadedSessionsKeepingReusableT2SAndVocoder()
                } else {
                    releaseLoadedSessions()
                }
            }
        }
    }

    fun encodePrompt(refAudio32k: FloatArray, speakerEmbedding: FloatTensorData): PromptEmbeddings {
        require(isInitialized()) { "CPU backend is not initialized." }
        return runPromptEncoder(PROMPT_ENCODER, refAudio32k, speakerEmbedding)
    }

    /** Release sessions used only while constructing reference conditioning. */
    fun releasePreparationSessions() {
        closeSessions { roleForSession(it) == InferenceModelRole.PROMPT_ENCODER }
    }

    /** Release all currently loaded graphs before the next feature phase. */
    fun releaseLoadedSessions() {
        v5DiT?.close()
        v2Htp?.close()
        closeSessions { true }
    }

    /** Release all T2S graphs when memory pressure makes the hot cache unsafe. */
    fun releaseT2SSessions() {
        closeSessions { roleForSession(it) == InferenceModelRole.T2S }
    }

    /** Keep reusable T2S decoders and VITS for a same-character sentence. */
    fun releaseLoadedSessionsKeepingReusableT2SAndVocoder() {
        if (!retainVocoderSessionAfterGeneration) {
            v5DiT?.close()
            v2Htp?.close()
        }
        closeSessions {
            when (roleForSession(it)) {
                InferenceModelRole.T2S -> !isReusableT2SSession(it)
                InferenceModelRole.VOCODER -> !retainVocoderSessionAfterGeneration
                else -> true
            }
        }
    }

    /** Release VITS before a new T2S phase when memory headroom disappears. */
    fun releaseVocoderSession() {
        v5DiT?.close()
        v2Htp?.close()
        closeSessions { roleForSession(it) == InferenceModelRole.VOCODER }
    }

    /** Load reusable T2S graphs before the first user request. */
    fun preloadT2SSessions() {
        if (!memoryHeadroomProvider()) return
        session(T2S_ENCODER)
        session(T2S_FIRST_STAGE_DECODER)
        session(T2S_STAGE_DECODER)
    }

    /** Shift VITS graph construction into background warmup for lower latency. */
    fun preloadVocoderSession() {
        if (!memoryHeadroomProvider()) return
        if (v5Acoustic != null) {
            if (v5DiT == null) session("v5-dit.onnx")
            session(V5AcousticRuntime.VOCODER)
        } else if (backend == ExecutionBackend.WEBGPU) {
            session(SplitVocoderAssets.ENCODER)
            if (v2Htp != null && qnnRuntimeDirectory() != null) {
                session(V2HtpVocoder.STEM)
                session(V2HtpVocoder.HEAD)
            } else session(SplitVocoderAssets.HIFIGAN)
        } else session(VITS)
    }

    /** Report whether a previously loaded decoder graph can serve the next sentence. */
    fun hasReusableT2SSessions(): Boolean = sessions.keys.any { isReusableT2SSession(it) }

    fun setRetainT2SSessionsAfterGeneration(retain: Boolean) {
        retainT2SSessionsAfterGeneration = retain
    }

    fun setRetainVocoderSessionAfterGeneration(retain: Boolean) {
        retainVocoderSessionAfterGeneration = retain
    }

    internal fun setMemoryHeadroomProvider(provider: () -> Boolean) {
        memoryHeadroomProvider = provider
    }

    private fun runPromptEncoder(
        modelName: String,
        refAudio32k: FloatArray,
        speakerEmbedding: FloatTensorData,
    ): PromptEmbeddings {
        val refAudio = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(refAudio32k),
            longArrayOf(1L, refAudio32k.size.toLong()),
        )
        refAudio.use {
            createFloatTensor(speakerEmbedding).use { svEmbedding ->
                session(modelName).runCancellable(
                    mapOf(
                        "ref_audio" to refAudio,
                        "sv_emb" to svEmbedding,
                    ),
                ).use { result ->
                    return PromptEmbeddings(
                        globalEmbedding = floatTensorData(result.get(0) as OnnxTensor),
                        advancedGlobalEmbedding = floatTensorData(result.get(1) as OnnxTensor),
                    )
                }
            }
        }
    }

    fun encodePromptBatch(
        referenceAudio32k: List<FloatArray>,
        speakerEmbeddings: List<FloatTensorData>,
    ): PromptEmbeddings {
        require(referenceAudio32k.isNotEmpty()) { "At least one reference audio is required." }
        require(referenceAudio32k.size == speakerEmbeddings.size) {
            "Reference audio and speaker embedding counts must match."
        }
        return runPromptBatchGraph(referenceAudio32k, speakerEmbeddings)
    }

    private fun runPromptBatchGraph(
        referenceAudio32k: List<FloatArray>,
        speakerEmbeddings: List<FloatTensorData>,
    ): PromptEmbeddings {
        val capability = requireNotNull(multiReferenceCapability()) {
            "The loaded model does not provide the multi-reference prompt encoder."
        }
        // This layer receives tensors rather than paths; validate the count without
        // inventing duplicate path values that would reject valid ordered slots.
        capability.validateReferenceCount(referenceAudio32k.size)
        val maxReferenceCount = capability.maxReferenceCount
        val placeholderAudioSamples = capability.placeholderAudioSamples
        val embeddingWidth = speakerEmbeddings.first().values.size
        require(speakerEmbeddings.all { it.values.size == embeddingWidth }) {
            "Speaker embedding dimensions must match."
        }
        val ownedTensors = mutableListOf<OnnxTensor>()
        val feed = linkedMapOf<String, OnnxTensorLike>()
        try {
            feed[REFERENCE_COUNT_INPUT] = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(longArrayOf(referenceAudio32k.size.toLong())),
                longArrayOf(1L),
            ).also(ownedTensors::add)
            for (index in 0 until maxReferenceCount) {
                // Unused slots still execute the unrolled source graph. Give
                // STFT nodes a valid zero waveform; the graph masks its output
                // before reduction, so this does not affect conditioning.
                val audio = referenceAudio32k.getOrNull(index)
                    ?: FloatArray(placeholderAudioSamples)
                val embedding = speakerEmbeddings.getOrNull(index)
                    ?: FloatTensorData(FloatArray(embeddingWidth), longArrayOf(1L, embeddingWidth.toLong()))
                feed["ref_audio_$index"] = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(audio),
                    longArrayOf(1L, audio.size.toLong()),
                ).also(ownedTensors::add)
                feed["sv_emb_$index"] = createFloatTensor(embedding).also(ownedTensors::add)
            }
            session(MULTI_REFERENCE_PROMPT_ENCODER).runCancellable(feed).use { result ->
                return PromptEmbeddings(
                    globalEmbedding = floatTensorData(result.get(0) as OnnxTensor),
                    advancedGlobalEmbedding = floatTensorData(result.get(1) as OnnxTensor),
                )
            }
        } finally {
            ownedTensors.forEach(OnnxTensor::close)
        }
    }

    override fun close() {
        v5DiT?.close()
        v5DiT = null
        v2Htp?.close()
        v2Htp = null
        sessions.values.forEach(OrtSession::close)
        sessions.clear()
        sessionFiles.clear()
        modelFiles = null
        v5Acoustic = null
        multiReferenceCapabilityResolved = false
        cachedMultiReferenceCapability = null
        retainT2SSessionsAfterGeneration = false
        retainVocoderSessionAfterGeneration = false
    }

    private fun session(modelName: String): OrtSession {
        sessions[modelName]?.let { return it }
        val selectedFile = requireNotNull(sessionFiles[modelName]) { "Session is not available: $modelName" }
        val role = roleForSession(modelName)
        val loadedSession = createSession(selectedFile, role)
        sessions[modelName] = loadedSession
        traceLogger.event(
            InferenceTraceEvent.Model(
                InferenceTraceRecord(
                    modelRole = role,
                    provider = if (backend == ExecutionBackend.WEBGPU && usesWebGpu(modelName)) InferenceProvider.WEBGPU else InferenceProvider.CPU,
                ),
            ),
        )
        return loadedSession
    }

    private fun closeSessions(predicate: (String) -> Boolean) {
        sessions.keys.filter(predicate).forEach { name ->
            sessions.remove(name)?.close()
        }
    }

    private fun roleForSession(modelName: String): InferenceModelRole =
        if (modelName.startsWith("v5-") || modelName in V2HtpVocoder.GRAPHS || modelName == SplitVocoderAssets.ENCODER || modelName == SplitVocoderAssets.HIFIGAN) InferenceModelRole.VOCODER
        else ModelRoles.roleForModelFile(modelName)

    private fun isReusableT2SSession(modelName: String): Boolean =
        modelName == T2S_ENCODER || modelName == T2S_FIRST_STAGE_DECODER || modelName == T2S_STAGE_DECODER

    private fun usesWebGpu(modelName: String): Boolean =
        modelName == SplitVocoderAssets.HIFIGAN || modelName == V5AcousticRuntime.VOCODER ||
            modelName == V5DiTRuntime.GRAPH || modelName == V5DiTRuntime.BODY

    private fun createLongTensor(data: LongTensorData): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(data.values), data.shape)

    private fun createFloatTensor(data: FloatTensorData): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data.values), data.shape)

    private fun tensorLike(result: OrtSession.Result, index: Int): OnnxTensorLike =
        result.get(index) as OnnxTensorLike

    private fun longTensorValues(tensor: OnnxTensor): LongArray {
        val values = LongArray(tensor.info.numElements.toInt())
        tensor.longBuffer.get(values)
        return values
    }

    private fun semanticTensorFromDecoderOutput(
        decoderResult: OrtSession.Result,
        generatedSteps: Int,
    ): OnnxTensor {
        val yTensor = decoderResult.get(0) as OnnxTensor
        val shape = yTensor.info.shape
        require(shape.size == 2) { "Expected decoder y shape [batch, tokens], got ${shape.contentToString()}" }
        require(shape[0] == 1L) { "Only batch size 1 is supported for prototype CPU generation." }

        val source = LongArray(yTensor.info.numElements.toInt())
        yTensor.longBuffer.get(source)
        val semantic = SemanticTokenSlicing.completedTokens(source, generatedSteps)
        SemanticTokenStability.requireNonEmpty(semantic)

        return OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(semantic),
            longArrayOf(1L, 1L, semantic.size.toLong()),
        )
    }

    private fun booleanValue(value: OnnxValue): Boolean =
        scalarBoolean(value.value)

    private fun decoderProgressCallback(maxDecoderSteps: Int): (Int, Int) -> Unit {
        var lastReportedStep = -8
        val safeMaxSteps = maxDecoderSteps.coerceAtLeast(1)
        val callback: (Int, Int) -> Unit = { generatedSteps, _ ->
            val overallPercent = GenerationProgress.decoderPercent(generatedSteps, safeMaxSteps)
            if (generatedSteps - lastReportedStep >= 8 || generatedSteps == safeMaxSteps) {
                lastReportedStep = generatedSteps
                reportProgress(
                    GenerationStage.RUNNING_INFERENCE,
                    "running_inference",
                    overallPercent,
                    "Decoder $generatedSteps/$safeMaxSteps",
                )
            }
        }
        callback(0, safeMaxSteps)
        return callback
    }

    private fun floatTensorData(tensor: OnnxTensor): FloatTensorData {
        val values = FloatArray(tensor.info.numElements.toInt())
        tensor.floatBuffer.get(values)
        return FloatTensorData(values, tensor.info.shape)
    }

    private fun scalarBoolean(value: Any?): Boolean {
        if (value is Boolean) {
            return value
        }
        val valueClass = value?.javaClass
        if (valueClass?.isArray == true && Array.getLength(value) > 0) {
            return scalarBoolean(Array.get(value, 0))
        }
        return false
    }

    companion object {
        private const val T2S_ENCODER = "t2s_encoder_fp32.onnx"
        private const val T2S_FIRST_STAGE_DECODER = "t2s_first_stage_decoder_fp32.onnx"
        private const val T2S_STAGE_DECODER = "t2s_stage_decoder_fp32.onnx"
        private const val PROMPT_ENCODER = "prompt_encoder_fp32.onnx"
        private const val MULTI_REFERENCE_PROMPT_ENCODER = "prompt_encoder_multi_fp32.onnx"
        private const val REFERENCE_COUNT_INPUT = "reference_count"
        private const val VITS = "vits_fp32.onnx"
        private const val AUTO_CPU_THREADS = 0

        fun cpuThreadPlan(availableProcessors: Int): CpuThreadPlan {
            val processors = availableProcessors.coerceAtLeast(1)
            return CpuThreadPlan(
                intraOpThreads = processors.coerceAtMost(8),
                interOpThreads = 1,
            )
        }

        fun cpuThreadPlan(
            availableProcessors: Int,
            role: InferenceModelRole,
            t2sThreadLimit: Int = AUTO_CPU_THREADS,
            vocoderThreadLimit: Int = AUTO_CPU_THREADS,
        ): CpuThreadPlan {
            val processors = availableProcessors.coerceAtLeast(1)
            val defaultLimit = when (role) {
                // T2S executes one autoregressive token at a time; a smaller
                // default pool avoids repeatedly scheduling idle workers.
                InferenceModelRole.T2S -> 4
                // VITS is parallel, but saturating every mobile core causes
                // contention and thermal throttling. The settings page can
                // still opt into all available processors explicitly.
                InferenceModelRole.VOCODER -> processors.coerceAtMost(6)
                else -> 8
            }
            val configuredLimit = when (role) {
                InferenceModelRole.T2S -> t2sThreadLimit
                InferenceModelRole.VOCODER -> vocoderThreadLimit
                else -> AUTO_CPU_THREADS
            }
            return CpuThreadPlan(
                intraOpThreads = resolveThreadLimit(
                    processors = processors,
                    configuredLimit = configuredLimit,
                    defaultLimit = defaultLimit,
                ),
                interOpThreads = 1,
            )
        }

        fun configureCpuSessionOptions(options: OrtSession.SessionOptions) {
            configureCpuSessionOptions(options, cpuThreadPlan(Runtime.getRuntime().availableProcessors()))
        }

        fun configureCpuSessionOptionsForRole(
            options: OrtSession.SessionOptions,
            role: InferenceModelRole,
        ) {
            configureCpuSessionOptionsForRole(
                options = options,
                role = role,
                t2sThreadLimit = AUTO_CPU_THREADS,
                vocoderThreadLimit = AUTO_CPU_THREADS,
            )
        }

        fun configureCpuSessionOptionsForRole(
            options: OrtSession.SessionOptions,
            role: InferenceModelRole,
            t2sThreadLimit: Int,
            vocoderThreadLimit: Int,
        ) {
            configureCpuSessionOptions(
                options,
                cpuThreadPlan(
                    availableProcessors = Runtime.getRuntime().availableProcessors(),
                    role = role,
                    t2sThreadLimit = t2sThreadLimit,
                    vocoderThreadLimit = vocoderThreadLimit,
                ),
            )
            options.setMemoryPatternOptimization(shouldUseMemoryPatternOptimization(role))
        }

        /** VITS input lengths change per sentence, so a stale pattern adds work. */
        internal fun shouldUseMemoryPatternOptimization(role: InferenceModelRole): Boolean =
            role != InferenceModelRole.VOCODER

        private fun resolveThreadLimit(
            processors: Int,
            configuredLimit: Int,
            defaultLimit: Int,
        ): Int = if (configuredLimit > AUTO_CPU_THREADS) {
            configuredLimit.coerceAtMost(processors)
        } else {
            defaultLimit.coerceAtMost(processors)
        }

        private fun configureCpuSessionOptions(
            options: OrtSession.SessionOptions,
            threadPlan: CpuThreadPlan,
        ) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setMemoryPatternOptimization(true)
            options.setCPUArenaAllocator(true)
            options.setIntraOpNumThreads(threadPlan.intraOpThreads)
            options.setInterOpNumThreads(threadPlan.interOpThreads)
        }

    }
}
