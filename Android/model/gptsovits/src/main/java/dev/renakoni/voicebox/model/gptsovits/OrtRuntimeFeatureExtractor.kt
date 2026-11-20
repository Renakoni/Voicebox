package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.LanguageTags

import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.MediaAudioDecoder
import dev.renakoni.voicebox.core.WavAudioReader

import android.app.ActivityManager
import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.LinkedHashMap

class OrtRuntimeFeatureExtractor(
    private val context: Context,
    private val runtimeAssets: RuntimeAssetRepository,
    private val backend: OrtCpuBackend,
    private val traceLogger: InferenceTraceLogger = InferenceTraceLogger.None,
    private val referenceCache: ReferenceConditioningCache = ReferenceConditioningCache(),
) : TtsInputPreparer, AutoCloseable {
    private data class AuxiliaryReferenceCacheKey(
        val audio: ReferenceAudioFingerprint,
        val modelInterfaceVersion: String,
        val preprocessingVersion: String,
        val backend: ExecutionBackend,
        val conditioningRole: InferenceModelRole,
    )

    private data class AuxiliaryReferenceConditioning(
        val audio32k: FloatArray,
        val speakerEmbedding: FloatTensorData,
    )

    private val environment: OrtEnvironment by lazy(LazyThreadSafetyMode.NONE) {
        OrtEnvironment.getEnvironment()
    }
    private var hubertSession: OrtSession? = null
    private var speakerSession: OrtSession? = null
    private val auxiliaryReferenceCache = object : LinkedHashMap<AuxiliaryReferenceCacheKey, AuxiliaryReferenceConditioning>(
        AUXILIARY_REFERENCE_CACHE_CAPACITY,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AuxiliaryReferenceCacheKey, AuxiliaryReferenceConditioning>,
        ): Boolean = size > AUXILIARY_REFERENCE_CACHE_CAPACITY
    }
    private val promptConditioningCache = PromptConditioningCache()
    private val audioFeatureCache = ReferenceAudioFeatureCache(runtimeAssets.referenceAudioCacheDirectory())
    private val audioCacheNamespace by lazy(LazyThreadSafetyMode.NONE) {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        "$REFERENCE_PREPROCESSING_VERSION|ort=${environment.version}|installed=$installed|backend=${backend.backend}"
    }

    init {
        // Re-evaluate the memory watermark after VITS finishes. The session
        // itself is the largest reusable object in the synthesis path.
        backend.setMemoryHeadroomProvider {
            canRetainT2SSessions(logDecision = false)
        }
    }

    private val robertaProvider: RobertaFeatureProvider? by lazy(LazyThreadSafetyMode.NONE) {
        RobertaFeatureProvider.tryCreate(
            environment = environment,
            runtimeRoot = runtimeAssets.runtimeRoot(),
            configureSessionOptions = { options ->
                backend.applySessionOptions(options, InferenceModelRole.ROBERTA)
            },
            onSessionCreated = {
                traceLogger.event(
                    InferenceTraceEvent.Model(
                        InferenceTraceRecord(
                            modelRole = InferenceModelRole.ROBERTA,
                            provider = InferenceProvider.CPU,
                        ),
                    ),
                )
            },
        )
    }

    private val textFeatureExtractorHolder = lazy(LazyThreadSafetyMode.NONE) {
        DefaultTextFeatureExtractor.fromAssets(context, robertaProvider)
    }
    private val textFeatureExtractor: TextFeatureExtractor get() = textFeatureExtractorHolder.value

    fun warmup(request: GenerationRequest) {
        // Construct the tokenizer/G2P facade and load RoBERTa before the
        // reference phase. Audio sessions are opened and closed serially.
        val timer = InferenceTimer(traceLogger)
        timer.measure("text_frontend_init_ms") {
            textFeatureExtractor
        }
        timer.measure("roberta_session_warmup_ms") {
            robertaProvider?.warmupSession()
        }
        if (File(request.referenceAudioPath).isFile && request.referenceText.isNotBlank()) {
            prepare(request)
        }
    }

    override fun prepare(request: GenerationRequest): TtsPreparedInput {
        request.context.checkCancelled()
        val timer = InferenceTimer(traceLogger)
        var retainRobertaSession = false
        try {
            val isV2ProPlus = backend.hasPromptEncoder()
            val primaryAudio = referenceAudioFingerprint(request.referenceAudioPath)
            val auxiliaryAudio = request.auxiliaryReferenceAudioPaths.map(::referenceAudioFingerprint)
            if (auxiliaryAudio.isNotEmpty()) {
                require(isV2ProPlus) { "Auxiliary references require a V2ProPlus prompt encoder." }
                val seen = mutableSetOf<String>()
                for (audio in auxiliaryAudio) {
                    require(audio.size >= 0) { "Auxiliary reference audio not found: ${audio.canonicalPath}" }
                    require(audio.canonicalPath != primaryAudio.canonicalPath) { "Auxiliary reference path duplicates the primary reference." }
                    require(seen.add(audio.canonicalPath)) { "Auxiliary reference paths must be unique." }
                }
            }

            val normalizedPromptLanguage = LanguageTags.normalize(request.promptLanguage)
            val modelVersion = modelInterfaceVersion(request.characterModel)
            val primaryCacheKey = ReferenceConditioningCacheKey(
                characterModelId = request.characterModel.id, backend = backend.backend, language = normalizedPromptLanguage,
                referenceAudio = primaryAudio, referenceText = request.referenceText, usesPromptEncoder = isV2ProPlus,
                modelInterfaceVersion = modelVersion, preprocessingVersion = REFERENCE_PREPROCESSING_VERSION,
                conditioningRole = if (isV2ProPlus) InferenceModelRole.PROMPT_ENCODER else InferenceModelRole.VOCODER,
            )
            val cachedPrimary = referenceCache.get(primaryCacheKey)
            val promptCacheKey = if (isV2ProPlus) {
                PromptConditioningCacheKey(
                    characterModelId = request.characterModel.id, backend = backend.backend, modelInterfaceVersion = modelVersion,
                    preprocessingVersion = REFERENCE_PREPROCESSING_VERSION, conditioningRole = InferenceModelRole.PROMPT_ENCODER,
                    primaryReferenceAudio = primaryAudio, auxiliaryReferenceAudioFingerprints = auxiliaryAudio,
                )
            } else {
                null
            }
            val cachedPrompt = promptCacheKey?.let(promptConditioningCache::get)
            val cacheHit = cachedPrimary != null && (!isV2ProPlus || cachedPrompt != null)
            val retainRoberta = canRetainRobertaSession()
            val canRetainSynthesisSessions = canRetainT2SSessions(logDecision = true)
            val canRetainT2S = canRetainSynthesisSessions
            val reuseRetainedT2S = cacheHit && canRetainT2S && backend.hasReusableT2SSessions()
            retainRobertaSession = retainRoberta

            // Keep the same-character synthesis sessions between audiobook
            // sentences. A cache miss still starts from a clean working set.
            backend.setRetainT2SSessionsAfterGeneration(
                shouldRetainT2SSessionsAfterGeneration(
                    memoryAllowsRetention = canRetainT2S,
                ),
            )
            backend.setRetainVocoderSessionAfterGeneration(
                shouldRetainVocoderSessionAfterGeneration(
                    memoryAllowsRetention = canRetainSynthesisSessions,
                ),
            )
            if (reuseRetainedT2S) {
                backend.releaseLoadedSessionsKeepingReusableT2SAndVocoder()
            } else {
                backend.releaseLoadedSessions()
            }
            closeTransientFeatureSessions(retainRoberta = retainRoberta)

            backend.reportProgress(
                GenerationStage.PREPARING_FEATURES,
                "preparing_features",
                GenerationProgress.TEXT_FEATURES_START,
                "Text features",
            )
            // Audiobook sentences normally differ, so target-text tensors are
            // rebuilt for every request rather than retained in a cache.
            val textFeatures = timer.measure("text_features_ms") {
                textFeatureExtractor.extract(
                    request.language,
                    GenieTextConventions.prepareSynthesisText(request.language, request.synthesisText),
                )
            }
            timer.event(
                InferenceTraceEvent.Model(
                    InferenceTraceRecord(
                        modelRole = InferenceModelRole.TEXT_FRONTEND,
                        provider = InferenceProvider.CPU,
                    ),
                ),
            )
            backend.reportProgress(
                GenerationStage.PREPARING_FEATURES,
                "preparing_features",
                GenerationProgress.TEXT_FEATURES_COMPLETE,
                "Reference conditioning",
            )
            timer.traceLongTensor(InferenceTensorMetric.TEXT_SEQ_HASH, textFeatures.phoneIds.values)
            timer.traceFloatTensor(InferenceTensorMetric.TEXT_BERT_HASH, textFeatures.bert.values)

            // Keep semantic/reference features independent from the ordered auxiliary bundle.
            timer.event(
                InferenceTraceEvent.Cache(
                    if (cacheHit) InferenceCacheStatus.HIT else InferenceCacheStatus.MISS,
                ),
            )
            backend.reportProgress(
                GenerationStage.PREPARING_FEATURES,
                "preparing_features",
                GenerationProgress.REFERENCE_CONDITIONING_START,
                "Reference audio",
            )
            val primary = timer.measure("reference_conditioning_ms") {
                if (cachedPrimary != null) {
                    cachedPrimary
                } else {
                    val refFeatures = timer.measure("reference_text_features_ms") {
                        textFeatureExtractor.extract(normalizedPromptLanguage, request.referenceText)
                    }
                    timer.traceLongTensor(InferenceTensorMetric.REF_SEQ_HASH, refFeatures.phoneIds.values)
                    timer.traceFloatTensor(InferenceTensorMetric.REF_BERT_HASH, refFeatures.bert.values)
                    // Keep RoBERTa warm only under the memory policy above. When
                    // it is not retained, close it before the audio feature models.
                    if (!retainRoberta) {
                        closeRobertaSession()
                    }
                    referenceCache.getOrPut(primaryCacheKey) {
                        computePrimaryReferenceConditioning(
                            request = request,
                            refFeatures = refFeatures,
                            isV2ProPlus = isV2ProPlus,
                            timer = timer,
                        )
                    }
                }
            }
            val reference = if (!isV2ProPlus) {
                primary
            } else {
                val prompt = timer.measure("prompt_conditioning_ms") {
                    cachedPrompt ?: promptConditioningCache.getOrPut(requireNotNull(promptCacheKey)) {
                        backend.reportProgress(
                            GenerationStage.PREPARING_FEATURES,
                            "preparing_features",
                            GenerationProgress.PROMPT_CONDITIONING_START,
                            "Prompt conditioning",
                        )
                        if (!retainRoberta) {
                            closeRobertaSession()
                        }
                        backend.releasePreparationSessions()
                        computePromptConditioning(
                            primary = primary,
                            auxiliaryAudio = auxiliaryAudio,
                            modelInterfaceVersion = modelVersion,
                            timer = timer,
                        )
                    }
                }
                primary.copy(
                    globalEmbedding = prompt.globalEmbedding,
                    advancedGlobalEmbedding = prompt.advancedGlobalEmbedding,
                )
            }

            backend.reportProgress(
                GenerationStage.PREPARING_FEATURES,
                "preparing_features",
                GenerationProgress.FEATURES_READY,
                "Features ready",
            )

            // Feature preparation can allocate hundreds of megabytes. Recheck
            // the watermark after that work so the next T2S request cannot
            // retain decoder graphs on an already pressured device.
            val canRetainT2SAfterPreparation = canRetainT2S &&
                canRetainT2SSessions(logDecision = true)
            backend.setRetainT2SSessionsAfterGeneration(
                shouldRetainT2SSessionsAfterGeneration(
                    memoryAllowsRetention = canRetainT2SAfterPreparation,
                ),
            )
            backend.setRetainVocoderSessionAfterGeneration(
                shouldRetainVocoderSessionAfterGeneration(
                    memoryAllowsRetention = canRetainT2SAfterPreparation,
                ),
            )
            if (!canRetainT2SAfterPreparation) {
                backend.releaseT2SSessions()
                backend.releaseVocoderSession()
            }

            return reference.toPreparedInput(textFeatures)
        } finally {
            closeTransientFeatureSessions(retainRoberta = retainRobertaSession)
            backend.releasePreparationSessions()
        }
    }

    private fun canRetainRobertaSession(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        return !memoryInfo.lowMemory &&
            memoryInfo.totalMem >= MIN_ROBERTA_CACHE_TOTAL_MEMORY_BYTES &&
            memoryInfo.availMem >= MIN_ROBERTA_CACHE_AVAILABLE_MEMORY_BYTES
    }

    private fun canRetainT2SSessions(logDecision: Boolean = false): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        val retained = !memoryInfo.lowMemory &&
            memoryInfo.totalMem >= MIN_T2S_CACHE_TOTAL_MEMORY_BYTES &&
            memoryInfo.availMem >= MIN_T2S_CACHE_AVAILABLE_MEMORY_BYTES
        if (logDecision) {
            traceLogger.event(
                InferenceTraceEvent.SessionCache(
                    role = InferenceModelRole.T2S,
                    retained = retained,
                    totalMemoryMiB = memoryInfo.totalMem / (1024L * 1024L),
                    availableMemoryMiB = memoryInfo.availMem / (1024L * 1024L),
                ),
            )
        }
        return retained
    }

    fun releaseLoadedSessions() {
        closeTransientFeatureSessions()
        backend.releasePreparationSessions()
    }

    private fun ensureHubertSession() {
        if (hubertSession == null) {
            val options = OrtSession.SessionOptions()
            try {
                backend.applySessionOptions(options, InferenceModelRole.HUBERT)
                hubertSession = environment.createSession(
                    runtimeAssets.hubertModelFile().absolutePath, options
                )
            } finally {
                options.close()
            }
            traceLogger.event(
                InferenceTraceEvent.Model(
                    InferenceTraceRecord(
                        modelRole = InferenceModelRole.HUBERT,
                        provider = InferenceProvider.CPU,
                    ),
                ),
            )
        }
    }

    private fun ensureSpeakerSession() {
        if (speakerSession == null) {
            val options = OrtSession.SessionOptions()
            try {
                backend.applySessionOptions(options, InferenceModelRole.SPEAKER_ENCODER)
                speakerSession = environment.createSession(
                    runtimeAssets.speakerEncoderFile().absolutePath, options
                )
            } finally {
                options.close()
            }
            traceLogger.event(
                InferenceTraceEvent.Model(
                    InferenceTraceRecord(
                        modelRole = InferenceModelRole.SPEAKER_ENCODER,
                        provider = InferenceProvider.CPU,
                    ),
                ),
            )
        }
    }

    private fun closeHubertSession() {
        hubertSession?.close()
        hubertSession = null
    }

    private fun closeSpeakerSession() {
        speakerSession?.close()
        speakerSession = null
    }

    private fun closeRobertaSession() {
        robertaProvider?.close()
    }

    private fun closeTransientFeatureSessions(retainRoberta: Boolean = false) {
        closeHubertSession()
        closeSpeakerSession()
        if (!retainRoberta) {
            closeRobertaSession()
        }
    }

    private fun computePrimaryReferenceConditioning(
        request: GenerationRequest,
        refFeatures: TextFeatures,
        isV2ProPlus: Boolean,
        timer: InferenceTimer,
    ): ReferenceConditioning {
        val isV5 = "v5-dit.onnx" in request.characterModel.modelFiles.sessionModels
        val refAudio32k = timer.measure("reference_audio_load_ms") {
            loadReferenceAudio(File(request.referenceAudioPath), 32000, silenceSeconds = if (isV5) 0f else 0.3f)
        }
        timer.traceFloatTensor(InferenceTensorMetric.REFERENCE_AUDIO_HASH, refAudio32k)
        val refAudio16k = timer.measure("reference_audio_resample_16k_ms") {
            val audio = WavAudioReader.resample(refAudio32k, 32000, 16000)
            // Upstream V5 appends 32000 * 0.3 zeros to HuBERT's 16 kHz input;
            // its reference spectrogram and mel use the unpadded waveform.
            if (isV5) audio.copyOf(audio.size + 9600) else audio
        }
        timer.traceFloatTensor(InferenceTensorMetric.REFERENCE_AUDIO_16K_HASH, refAudio16k)
        val audioCacheKey = ReferenceAudioFeatureCache.key(
            InferenceTraceHash.sha256(refAudio16k), "$audioCacheNamespace|speaker=$isV2ProPlus",
            runtimeAssets.referenceAudioFeatureModelFiles(),
        )
        val cachedAudio = timer.measure("reference_audio_cache_ms") { audioFeatureCache.load(audioCacheKey) }
        timer.event(InferenceTraceEvent.Model(InferenceTraceRecord(
            modelRole = InferenceModelRole.HUBERT,
            provider = InferenceProvider.CPU,
            cacheStatus = if (cachedAudio != null) InferenceCacheStatus.HIT else InferenceCacheStatus.MISS,
        )))
        val sslContent = cachedAudio?.sslContent ?: timer.measure("hubert_ms") {
            ensureHubertSession()
            try {
                runHubert(refAudio16k)
            } finally {
                closeHubertSession()
            }
        }
        backend.reportProgress(
            GenerationStage.PREPARING_FEATURES,
            "preparing_features",
            GenerationProgress.HUBERT_COMPLETE,
            "HuBERT",
        )
        timer.traceFloatTensor(InferenceTensorMetric.SSL_CONTENT_HASH, sslContent.values)

        val zeroEmb = FloatTensorData(FloatArray(0), longArrayOf(0L))
        val refAudioTensor = if (isV2ProPlus) {
            null
        } else {
            FloatTensorData(
                values = refAudio32k,
                shape = longArrayOf(1L, refAudio32k.size.toLong()),
            )
        }
        val primarySpeakerEmbedding = if (isV2ProPlus) {
            cachedAudio?.speakerEmbedding ?: timer.measure("speaker_encoder_ms") {
                ensureSpeakerSession()
                try {
                    runSpeakerEncoder(refAudio16k)
                } finally {
                    closeSpeakerSession()
                }
            }
        } else {
            null
        }
        if (cachedAudio == null) {
            request.context.checkCancelled()
            timer.measure("reference_audio_cache_write_ms") {
                audioFeatureCache.store(audioCacheKey, ReferenceAudioFeatures(sslContent, primarySpeakerEmbedding))
            }
        }
        backend.reportProgress(
            GenerationStage.PREPARING_FEATURES,
            "preparing_features",
            GenerationProgress.REFERENCE_CONDITIONING_COMPLETE,
            "Reference ready",
        )
        return ReferenceConditioning(
            refSeq = refFeatures.phoneIds,
            refBert = refFeatures.bert,
            sslContent = sslContent,
            globalEmbedding = zeroEmb,
            advancedGlobalEmbedding = zeroEmb,
            refAudio32k = refAudioTensor,
            primaryAudio32k = refAudio32k,
            primaryAudio16k = refAudio16k,
            primarySpeakerEmbedding = primarySpeakerEmbedding,
        )
    }

    private fun computePromptConditioning(
        primary: ReferenceConditioning,
        auxiliaryAudio: List<ReferenceAudioFingerprint>,
        modelInterfaceVersion: String,
        timer: InferenceTimer,
    ): PromptEmbeddings {
        timer.traceFloatTensor(
            speakerEmbeddingMetric(0),
            requireNotNull(primary.primarySpeakerEmbedding).values,
        )
        val promptEmbeddings = if (auxiliaryAudio.isEmpty()) {
            timer.measure("prompt_encoder_ms") {
                backend.encodePrompt(
                    requireNotNull(primary.primaryAudio32k),
                    requireNotNull(primary.primarySpeakerEmbedding),
                )
            }
        } else {
            val auxiliaryConditioning = run {
                ensureSpeakerSession()
                try {
                    auxiliaryAudio.mapIndexed { index, audio ->
                        loadAuxiliaryReference(audio, modelInterfaceVersion).also { conditioning ->
                            timer.traceFloatTensor(
                                speakerEmbeddingMetric(index + 1),
                                conditioning.speakerEmbedding.values,
                            )
                        }
                    }
                } finally {
                    closeSpeakerSession()
                }
            }
            val referenceAudio32k = buildList {
                add(requireNotNull(primary.primaryAudio32k))
                addAll(auxiliaryConditioning.map(AuxiliaryReferenceConditioning::audio32k))
            }
            val speakerEmbeddings = buildList {
                add(requireNotNull(primary.primarySpeakerEmbedding))
                addAll(auxiliaryConditioning.map(AuxiliaryReferenceConditioning::speakerEmbedding))
            }
            timer.measure("prompt_encoder_ms") {
                backend.encodePromptBatch(
                    referenceAudio32k = referenceAudio32k,
                    speakerEmbeddings = speakerEmbeddings,
                )
            }
        }
        timer.traceFloatTensor(
            InferenceTensorMetric.GLOBAL_EMBEDDING_HASH,
            promptEmbeddings.globalEmbedding.values,
        )
        timer.traceFloatTensor(
            InferenceTensorMetric.ADVANCED_GLOBAL_EMBEDDING_HASH,
            promptEmbeddings.advancedGlobalEmbedding.values,
        )
        return promptEmbeddings
    }

    // Keep each reference slot distinguishable from the Python parity trace.
    private fun speakerEmbeddingMetric(referenceIndex: Int): InferenceTensorMetricName =
        NamedInferenceTensorMetric("speaker_embedding_${referenceIndex}_hash")

    private fun loadAuxiliaryReference(
        audio: ReferenceAudioFingerprint,
        modelInterfaceVersion: String,
    ): AuxiliaryReferenceConditioning {
        val cacheKey = AuxiliaryReferenceCacheKey(
            audio = audio,
            modelInterfaceVersion = modelInterfaceVersion,
            preprocessingVersion = REFERENCE_PREPROCESSING_VERSION,
            backend = backend.backend,
            conditioningRole = InferenceModelRole.SPEAKER_ENCODER,
        )
        auxiliaryReferenceCache[cacheKey]?.let { return it }
        val audio32k = loadReferenceAudio(File(cacheKey.audio.canonicalPath), 32000)
        val audio16k = WavAudioReader.resample(audio32k, 32000, 16000)
        return AuxiliaryReferenceConditioning(
            audio32k = audio32k,
            speakerEmbedding = runSpeakerEncoder(audio16k),
        ).also { conditioning ->
            auxiliaryReferenceCache[cacheKey] = conditioning
        }
    }

    private fun modelInterfaceVersion(characterModel: CharacterModel): String = with(characterModel.modelFiles) {
        "$variantName|storage=$storageVersion|sessions=${sessionModels.joinToString(",")}|optional_sessions=${optionalSessionModels.joinToString(",")}"
    }

    private fun referenceAudioFingerprint(path: String): ReferenceAudioFingerprint {
        val input = File(path)
        val file = runCatching { input.canonicalFile }.getOrElse { input.absoluteFile }
        return if (file.isFile) ReferenceAudioFingerprint(file.path, file.length(), file.lastModified())
            else ReferenceAudioFingerprint(file.path, -1L, -1L)
    }

    private fun ReferenceConditioning.toPreparedInput(textFeatures: TextFeatures): TtsPreparedInput =
        TtsPreparedInput(
            refSeq = refSeq,
            textSeq = textFeatures.phoneIds,
            refBert = refBert,
            textBert = textFeatures.bert,
            sslContent = sslContent,
            globalEmbedding = globalEmbedding,
            advancedGlobalEmbedding = advancedGlobalEmbedding,
            refAudio32k = refAudio32k,
        )

    private fun loadReferenceAudio(file: File, targetRate: Int, silenceSeconds: Float = 0.3f): FloatArray {
        val decoded = MediaAudioDecoder.decodeMonoPcm(file) ?: WavAudioReader.readMonoPcm(file)
        val resampled = WavAudioReader.resample(decoded.samples, decoded.sampleRate, targetRate)
        return if (silenceSeconds == 0f) resampled else WavAudioReader.appendSilence(resampled, targetRate, silenceSeconds)
    }

    private fun runHubert(audio16k: FloatArray): FloatTensorData {
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(audio16k), longArrayOf(1L, audio16k.size.toLong()))
        input.use {
            requireNotNull(hubertSession).runCancellable(mapOf("input_values" to input)).use { result ->
                return tensorData(result.get(0) as OnnxTensor)
            }
        }
    }

    private fun runSpeakerEncoder(audio16k: FloatArray): FloatTensorData {
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(audio16k), longArrayOf(1L, audio16k.size.toLong()))
        input.use {
            requireNotNull(speakerSession).runCancellable(mapOf("waveform" to input)).use { result ->
                return tensorData(result.get(0) as OnnxTensor)
            }
        }
    }

    private fun tensorData(tensor: OnnxTensor): FloatTensorData {
        val values = FloatArray(tensor.info.numElements.toInt())
        tensor.floatBuffer.get(values)
        return FloatTensorData(values, tensor.info.shape)
    }

    override fun close() {
        if (textFeatureExtractorHolder.isInitialized()) textFeatureExtractor.close()
        hubertSession?.close()
        speakerSession?.close()
        hubertSession = null
        speakerSession = null
        referenceCache.clear()
        promptConditioningCache.clear()
        auxiliaryReferenceCache.clear()
        robertaProvider?.close()
    }

    companion object {
        internal fun shouldRetainT2SSessionsAfterGeneration(
            memoryAllowsRetention: Boolean,
        ): Boolean = memoryAllowsRetention

        internal fun shouldRetainVocoderSessionAfterGeneration(
            memoryAllowsRetention: Boolean,
        ): Boolean = memoryAllowsRetention

        private const val REFERENCE_PREPROCESSING_VERSION =
            "reference-conditioning-v2-soxr-hq-silence-0.3"
        // Auxiliary references are a per-prompt working set. The prompt cache
        // keeps the combined result, so retaining old auxiliary audio only
        // increases memory use for the audiobook path.
        private const val AUXILIARY_REFERENCE_CACHE_CAPACITY =
            AudiobookCachePolicy.MAX_AUXILIARY_REFERENCE_ENTRIES
        private const val MIN_ROBERTA_CACHE_TOTAL_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MIN_ROBERTA_CACHE_AVAILABLE_MEMORY_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_T2S_CACHE_TOTAL_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L
        // Retain the decoder graphs only when the device has enough headroom;
        // the cache is bounded to the active character model.
        private const val MIN_T2S_CACHE_AVAILABLE_MEMORY_BYTES = 2100L * 1024L * 1024L
    }
}
