package dev.renakoni.voicebox

import dev.renakoni.voicebox.moremodels.MingModel
import dev.renakoni.voicebox.moremodels.MingPackageRepository
import dev.renakoni.voicebox.moremodels.MingBackendRuntime

import dev.renakoni.voicebox.i18n.UiText
import dev.renakoni.voicebox.i18n.UiLanguageContext
import dev.renakoni.voicebox.i18n.userMessage
import dev.renakoni.voicebox.i18n.userText
import dev.renakoni.voicebox.i18n.errorUi

import android.Manifest
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.renakoni.voicebox.api.ActiveDefaults
import dev.renakoni.voicebox.api.InferResult
import dev.renakoni.voicebox.api.LocalHttpApiServer
import dev.renakoni.voicebox.checkpoint.CheckpointImporter
import dev.renakoni.voicebox.checkpoint.CharacterVoiceEditor
import dev.renakoni.voicebox.inference.BackendRuntime
import dev.renakoni.voicebox.inference.BackendRuntimeCallbacks
import dev.renakoni.voicebox.inference.CharacterModel
import dev.renakoni.voicebox.inference.ExecutionBackend
import dev.renakoni.voicebox.inference.DeviceAcceleration
import dev.renakoni.voicebox.inference.GenerationRequest
import dev.renakoni.voicebox.inference.GenerationContext
import dev.renakoni.voicebox.inference.GenerationCancelledException
import dev.renakoni.voicebox.inference.EmptySemanticException
import dev.renakoni.voicebox.inference.GenerationStage
import dev.renakoni.voicebox.inference.InferenceTraceLogger
import dev.renakoni.voicebox.inference.LogcatInferenceTraceLogger
import dev.renakoni.voicebox.inference.ModelAssetRepository
import dev.renakoni.voicebox.inference.OrtSessionRuntime
import dev.renakoni.voicebox.inference.OrtCpuBackend
import dev.renakoni.voicebox.inference.OrtRuntimeFeatureExtractor
import dev.renakoni.voicebox.inference.RuntimeAssetRepository
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceBoxBackendService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))

    private val binder = LocalBinder()
    private val apiClients = linkedSetOf<Messenger>()
    private val listeners = linkedSetOf<BackendServiceListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val apiMessenger = Messenger(ApiHandler())
    private val generationGate = GenerationRequestGate()
    private val officialDownloadState = MutableStateFlow(OfficialDownloadState())
    val officialDownload = officialDownloadState.asStateFlow()
    @Volatile private var destroyed = false
    @Volatile private var warmupContext: GenerationContext? = null

    private data class WarmupKey(
        val backend: ExecutionBackend,
        val modelId: String,
    )

    private val warmupLock = Any()
    private var queuedWarmupKey: WarmupKey? = null
    private var completedWarmupKey: WarmupKey? = null

    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var modelRepository: ModelAssetRepository
    private lateinit var runtimeRepository: RuntimeAssetRepository
    private lateinit var localHttpApiServer: LocalHttpApiServer
    lateinit var deviceAcceleration: DeviceAcceleration
        private set

    private val runtimes = linkedMapOf<ExecutionBackend, BackendRuntime>()
    // Accessed only by the inference worker. Do not retain two complete voice runtimes.
    private var workerBackend: ExecutionBackend? = null
    private val enabledBackends = MutableStateFlow<Set<ExecutionBackend>>(emptySet())
    val backendCapabilities = enabledBackends.asStateFlow()

    @Volatile
    private var resourceReloadPending = false

    @Volatile
    private var cacheClearPending = false

    @Volatile
    private var cacheClearScheduled = false

    @Volatile
    private var activeDefaults: ActiveDefaults? = null

    @Volatile
    private var state: BackendServiceState = BackendServiceState(
        message = "",
    )

    override fun onCreate() {
        super.onCreate()
        settingsRepository = AppSettingsRepository(this)
        modelRepository = ModelAssetRepository(this)
        runtimeRepository = RuntimeAssetRepository(this)
        deviceAcceleration = DeviceAcceleration(this)
        localHttpApiServer = LocalHttpApiServer(
            object : LocalHttpApiServer.Bridge {
                override fun currentState(): BackendServiceState = this@VoiceBoxBackendService.currentState()

                override fun availableModels(): List<CharacterModel> = modelRepository.discoverCharacterModels()

                override fun supportedBackends(): Set<ExecutionBackend> = this@VoiceBoxBackendService.supportedBackends()

                override fun supportsAuxiliaryReferences(model: CharacterModel): Boolean =
                    model.modelFiles.maxAuxiliaryReferenceCount > 0 &&
                        File(
                            modelRepository.modelRootDirectory(),
                            "${model.relativeModelDirectory}/prompt_encoder_multi_fp32.onnx",
                        ).isFile

                override fun activeDefaults(): ActiveDefaults? = this@VoiceBoxBackendService.activeDefaults

                override fun infer(backend: ExecutionBackend, request: GenerationRequest): Boolean =
                    synthesize(backend, request)

                override fun inferBlocking(backend: ExecutionBackend, request: GenerationRequest, timeoutMs: Long): InferResult =
                    this@VoiceBoxBackendService.inferBlocking(backend, request, timeoutMs)

                override fun cancel(requestId: String): Boolean = cancelGeneration(requestId)
            },
        )
        val traceLogger = LogcatInferenceTraceLogger()
        val executionBackends = mutableListOf(ExecutionBackend.CPU)
        if (OrtProvider.WEBGPU in OrtEnvironment.getAvailableProviders()) {
            executionBackends += ExecutionBackend.WEBGPU
        }
        for (executionBackend in executionBackends) {
            runtimes[executionBackend] = OrtSessionRuntime(
                modelRepository = modelRepository,
                runtimeRepository = runtimeRepository,
                backendEngine = OrtCpuBackend(
                    backend = executionBackend,
                    configureSessionOptions = OrtCpuBackend::configureCpuSessionOptions,
                    configureRoleSessionOptions = { options, role ->
                        OrtCpuBackend.configureCpuSessionOptionsForRole(
                            options = options,
                            role = role,
                            t2sThreadLimit = settingsRepository.t2sCpuThreads,
                            vocoderThreadLimit = settingsRepository.vocoderCpuThreads,
                        )
                    },
                    traceLogger = traceLogger,
                    useNativeDecoder = true,
                    qnnRuntimeDirectory = {
                        deviceAcceleration.runtimeDirectory.takeIf { settingsRepository.deviceAccelerationEnabled }
                    },
                    onNpuUnavailable = deviceAcceleration::modelUnavailable,
                ),
                featureExtractorFactory = { backend ->
                    OrtRuntimeFeatureExtractor(
                        context = this,
                        runtimeAssets = runtimeRepository,
                        backend = backend,
                        traceLogger = traceLogger,
                    )
                },
                traceLogger = traceLogger,
            )
        }
        worker.execute {
            val webGpu = ExecutionBackend.WEBGPU in runtimes && canInitializeWebGpu()
            enabledBackends.value = if (webGpu) runtimes.keys.toSet() else setOf(ExecutionBackend.CPU)
            deviceAcceleration.detect(webGpu)
        }
        createNotificationChannel()
        startForegroundCompat(NOTIFICATION_ID, buildNotification(state))
        syncLocalHttpApiServer()
    }

    private fun canInitializeWebGpu(): Boolean = try {
        OrtEnvironment.getEnvironment() // Register ORT's logger before initializing the provider.
        OrtSession.SessionOptions().use { it.addWebGPU(emptyMap()) }
        true
    } catch (error: OrtException) {
        Log.i(TIMING_LOG_TAG, "WebGPU initialization failed: ${error.message}")
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_RESOURCES -> requestResourceReload()
            ACTION_RELOAD_INFERENCE_SETTINGS -> requestResourceReload()
            ACTION_CLEAR_CACHE -> requestCacheClear()
        }
        syncLocalHttpApiServer()
        updateNotification(state)
        return START_STICKY
    }

    private fun startForegroundCompat(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder =
        if (intent?.action == BackendApiContract.ACTION_BIND_API) {
            apiMessenger.binder
        } else {
            binder
        }

    override fun onDestroy() {
        destroyed = true
        generationGate.stop()
        warmupContext?.cancel("SERVICE_DESTROYED")
        localHttpApiServer.close()
        // Session ownership never leaves the worker, including during teardown.
        worker.execute {
            runtimes.values.forEach(BackendRuntime::close)
            runtimes.clear()
            Log.i(TIMING_LOG_TAG, "runtime_closed_after_worker_return")
        }
        worker.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // Hiding the UI is normal for this foreground service and should not
        // evict the warm RoBERTa session. Trim only for actual memory pressure.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            runCatching {
                worker.execute {
                    runtimes.values.forEach(BackendRuntime::trimMemory)
                    synchronized(warmupLock) {
                        // Trimming closes graph sessions, so a later idle
                        // period must be allowed to schedule warmup again.
                        completedWarmupKey = null
                        queuedWarmupKey = null
                    }
                }
            }
        }
        super.onTrimMemory(level)
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceBoxBackendService = this@VoiceBoxBackendService
    }

    fun addListener(listener: BackendServiceListener) {
        listeners += listener
        listener.onStateChanged(state)
    }

    fun removeListener(listener: BackendServiceListener) {
        listeners -= listener
    }

    fun currentState(): BackendServiceState = state

    fun supportedBackends(): Set<ExecutionBackend> = enabledBackends.value.ifEmpty { setOf(ExecutionBackend.CPU) }

    fun setActiveDefaults(defaults: ActiveDefaults?) {
        val supportedDefaults = defaults?.takeIf { it.backend in supportedBackends() }
            ?: defaults?.copy(backend = ExecutionBackend.CPU)
        val previous = activeDefaults
        if (previous != null && (previous.modelId != supportedDefaults?.modelId || previous.backend != supportedDefaults?.backend)) {
            generationGate.invalidate("VOICE_CHANGED")
            warmupContext?.cancel("VOICE_CHANGED")
        }
        activeDefaults = supportedDefaults
        supportedDefaults?.let(::scheduleWarmup)
    }

    fun synthesize(
        backend: ExecutionBackend,
        request: GenerationRequest,
    ): Boolean {
        if (backend !in supportedBackends()) return false
        if (!generationGate.tryAcquire(request.context)) {
            return false
        }
        try {
            worker.execute {
                runSynthesis(backend, request)
            }
        } catch (error: Throwable) {
            generationGate.release(request.context)
            throw error
        }
        return true
    }

    fun inferBlocking(
        backend: ExecutionBackend,
        request: GenerationRequest,
        timeoutMs: Long,
    ): InferResult {
        if (backend !in supportedBackends()) {
            return InferResult.Error("Backend is not enabled.")
        }
        if (!generationGate.tryAcquire(request.context)) {
            return InferResult.Busy
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val resultHolder = java.util.concurrent.atomic.AtomicReference<InferResult>(InferResult.Timeout)
        try {
            worker.execute {
                runSynthesis(backend, request) { res ->
                    resultHolder.set(res)
                    latch.countDown()
                }
            }
        } catch (error: Throwable) {
            generationGate.release(request.context)
            throw error
        }
        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                val cancelled = generationGate.cancel(request.context.requestId, "TIMEOUT")
                return if (cancelled) InferResult.Timeout else resultHolder.get()
            }
        } catch (error: InterruptedException) {
            generationGate.cancel(request.context.requestId, "CLIENT_INTERRUPTED")
            Thread.currentThread().interrupt()
            return InferResult.Error("Request interrupted.", "CANCELLED")
        }
        return resultHolder.get()
    }

    fun cancelGeneration(requestId: String): Boolean = generationGate.cancel(requestId, "CANCELLED")

    fun downloadOfficialModel(id: String): Boolean {
        val catalog = OfficialModelCatalog(this)
        val model = catalog.models.firstOrNull { it.id == id }
        if (model == null && id != MingModel.ID) return false
        val previous = officialDownloadState.value
        officialDownloadState.value = OfficialDownloadState(id, UiText(R.string.download_preparing), running = true)
        val accepted = importOnWorker<Unit>(onComplete = { result ->
            officialDownloadState.value = result.fold(
                { OfficialDownloadState(id, UiText(R.string.download_complete), completed = true) },
                { error ->
                    Log.w("OfficialModels", "Model download failed", error)
                    OfficialDownloadState(id, error = if (error is java.util.concurrent.CancellationException)
                        UiText(R.string.download_cancelled) else error.userText()
                        ?: UiText(R.string.download_failed))
                })
        }) { context, _ ->
            if (id == MingModel.ID) {
                val ming = MingPackageRepository(this)
                require(ming.supported) { getString(R.string.ming_device_unsupported) }
                ming.install(context::checkCancelled) { percent ->
                    officialDownloadState.value = OfficialDownloadState(id, UiText(R.string.ming_downloading), percent, running = true)
                }
            } else {
                OfficialModelInstaller(requireNotNull(getExternalFilesDir(null)), catalog, assets::open).install(requireNotNull(model), context::checkCancelled) {
                    officialDownloadState.value = it
                }
            }
        }
        if (!accepted) officialDownloadState.value = previous
        return accepted
    }

    /** Import on the session owner thread; a replacement must never race inference or warmup. */
    fun importRuntimePackage(
        uri: android.net.Uri,
        onComplete: (Result<Unit>) -> Unit,
    ): Boolean = importOnWorker(onComplete) { context, _ ->
        contentResolver.openInputStream(uri)?.use { input ->
            ResourcePackageImporter(requireNotNull(getExternalFilesDir(null))).importRuntime(input, context::checkCancelled)
        } ?: errorUi(UiText(R.string.resource_open_failed))
    }

    fun importCheckpointPair(
        name: String,
        gpt: android.net.Uri,
        sovits: android.net.Uri,
        onProgress: (UiText) -> Unit,
        onComplete: (Result<CheckpointImporter.Result>) -> Unit,
        reference: CheckpointImporter.Reference? = null,
        replaceCharacterId: String? = null,
        gptFilename: String = "",
        sovitsFilename: String = "",
        architectureHint: String? = null,
    ): Boolean = importOnWorker(onComplete, onProgress) { context, progress ->
        CheckpointImporter(requireNotNull(getExternalFilesDir(null))) { file ->
            assets.open(file)
        }.import(name,
            openGpt = { contentResolver.openInputStream(gpt) ?: errorUi(UiText(R.string.gpt_open_failed)) },
            openSovits = { contentResolver.openInputStream(sovits) ?: errorUi(UiText(R.string.sovits_open_failed)) },
            progress = progress, checkCancelled = context::checkCancelled, reference = reference,
            replaceCharacterId = replaceCharacterId, gptFilename = gptFilename, sovitsFilename = sovitsFilename,
            architectureHint = architectureHint)
    }

    fun editCharacterReference(id: String, name: String, reference: CheckpointImporter.Reference,
        version: String,
        onComplete: (Result<CheckpointImporter.Result>) -> Unit): Boolean = importOnWorker(onComplete) { _, _ ->
        CharacterVoiceEditor(requireNotNull(getExternalFilesDir(null))).updateReference(id, name, reference, version)
        CheckpointImporter.Result(id, name.trim(), version)
    }

    private fun <T> importOnWorker(
        onComplete: (Result<T>) -> Unit,
        onProgress: (UiText) -> Unit = {},
        operation: (GenerationContext, (UiText) -> Unit) -> T,
    ): Boolean {
        if (generationGate.isClaimed() || destroyed) return false
        setActiveDefaults(null)
        val context = GenerationContext()
        if (!generationGate.tryAcquire(context)) return false
        publishState(GenerationStage.INSTALLING_MODEL, true, state.requestedBackend,
            state.resolvedBackend, state.runtimeLabel, getString(R.string.resources_importing), context = context)
        worker.execute {
            val result = runCatching {
                context.checkCancelled()
                runtimes.values.forEach(BackendRuntime::close)
                workerBackend = null
                synchronized(warmupLock) { completedWarmupKey = null; queuedWarmupKey = null }
                operation(context) { message -> mainHandler.post { onProgress(message) } }
            }
            generationGate.release(context)
            publishState(GenerationStage.IDLE, false, state.requestedBackend,
                state.resolvedBackend, state.runtimeLabel, "", initializedModelId = null)
            mainHandler.post { onComplete(result) }
        }
        return true
    }

    private fun runSynthesis(
        backend: ExecutionBackend,
        request: GenerationRequest,
        onComplete: ((InferResult) -> Unit)? = null,
    ) {
        val ming = MingModel.matches(request.characterModel)
        val runtime = if (ming) MingBackendRuntime(this, backend) else runtimes[backend]
        val startedAtMs = SystemClock.elapsedRealtime()
        val context = request.context
        Log.i(TIMING_LOG_TAG, "request_id: ${context.requestId}")
        Log.i(TIMING_LOG_TAG, "generation_epoch: ${context.generationEpoch}")
        try {
            context.checkCancelled()
            if (ming) {
                runtimes.values.forEach(BackendRuntime::close)
                workerBackend = null
                synchronized(warmupLock) { completedWarmupKey = null }
            } else selectWorkerBackend(backend)
            val activeRuntime = requireNotNull(runtime) {
                getString(R.string.prototype_backend_unavailable, backend.label)
            }
            val result = withActiveGenerationResources {
                context.run {
                    activeRuntime.generate(
                        request = request,
                        callbacks = object : BackendRuntimeCallbacks {
                            override fun onStage(
                                stage: GenerationStage,
                                message: String,
                                progressPercent: Int?,
                                progressLabel: String?,
                            ) {
                                context.checkCancelled()
                                publishState(
                                    context = context,
                                    stage = stage,
                                    busy = true,
                                    requestedBackend = backend,
                                    resolvedBackend = activeRuntime.backend,
                                    runtimeLabel = activeRuntime.runtimeLabel,
                                    initializedModelId = request.characterModel.id,
                                    message = stageMessage(stage, request.characterModel, message),
                                    progressPercent = progressPercent,
                                    progressLabel = progressLabel,
                                    generationDurationMs = null,
                                )
                            }
                        },
                    )
                }
            }
            val completedAtMs = System.currentTimeMillis()
            val durationMs = SystemClock.elapsedRealtime() - startedAtMs

            val delivered = generationGate.deliver(context) {
                // Inference and its cleanup have returned. Ready state and the
                // blocking callback must not precede admission of the next request.
                generationGate.release(context)
                publishState(
                    context = context,
                    stage = GenerationStage.COMPLETED,
                    busy = false,
                    requestedBackend = backend,
                    resolvedBackend = activeRuntime.backend,
                    runtimeLabel = activeRuntime.runtimeLabel,
                    initializedModelId = request.characterModel.id,
                    latestOutputFilePath = result.file.absolutePath,
                    progressPercent = 100,
                    progressLabel = null,
                    generationDurationMs = durationMs,
                    lastCompletedAtMs = completedAtMs,
                    message = getString(
                        R.string.prototype_service_completed,
                        formatAudioDuration(result.audioSamples, result.sampleRate),
                    ),
                )
                onComplete?.invoke(InferResult.Success(result.file,
                    result.audioSamples.toLong() * 1000 / result.sampleRate))
            }
            if (!delivered) {
                result.file.delete()
                throw GenerationCancelledException(context.cancellationReason ?: "STALE_GENERATION")
            }
        } catch (error: Throwable) {
            val cancelled = context.cancellationReason != null || error is GenerationCancelledException
            val errorCode = when {
                cancelled -> context.cancellationReason ?: "CANCELLED"
                error is EmptySemanticException -> "EMPTY_SEMANTIC"
                else -> "INFERENCE_FAILED"
            }
            generationGate.release(context)
            publishState(
                requestId = context.requestId,
                generationEpoch = context.generationEpoch,
                errorCode = errorCode,
                stage = if (cancelled) GenerationStage.CANCELLED else GenerationStage.ERROR,
                busy = false,
                requestedBackend = backend,
                resolvedBackend = runtime?.backend ?: backend,
                runtimeLabel = runtime?.runtimeLabel ?: backend.label,
                initializedModelId = request.characterModel.id,
                progressPercent = null,
                progressLabel = null,
                generationDurationMs = SystemClock.elapsedRealtime() - startedAtMs,
                latestOutputFilePath = null,
                message = if (cancelled) getString(R.string.generation_cancelled)
                    else error.userMessage(resources) ?: getString(R.string.generation_failed),
            )
            onComplete?.invoke(InferResult.Error(error.userMessage(resources) ?: error.message ?: error.javaClass.simpleName, errorCode))
        } finally {
            Log.i(TIMING_LOG_TAG, "request_total_ms=${SystemClock.elapsedRealtime() - startedAtMs} ms")
            generationGate.release(context)
            if (!destroyed && resourceReloadPending) {
                reloadInferenceResources()
            }
            if (!destroyed && cacheClearPending) {
                cacheClearPending = false
                performCacheClear()
            }
        }
    }

    private fun requestResourceReload() {
        if (destroyed) return
        generationGate.invalidate("RESOURCES_CHANGED")
        warmupContext?.cancel("RESOURCES_CHANGED")
        if (state.busy || generationGate.isClaimed()) {
            resourceReloadPending = true
        } else {
            // Resource/session teardown must share the inference worker with
            // warmup and generation; doing it on the service main thread can
            // race the queued warmup and close an ORT session underneath it.
            worker.execute(::reloadInferenceResources)
        }
    }

    private fun reloadInferenceResources() {
        if (generationGate.isClaimed()) {
            resourceReloadPending = true
            return
        }
        runtimes.values.forEach(BackendRuntime::close)
        resourceReloadPending = false
        synchronized(warmupLock) {
            completedWarmupKey = null
            queuedWarmupKey = null
        }
        activeDefaults?.let(::scheduleWarmup)
    }

    private fun requestCacheClear() {
        if (destroyed) return
        generationGate.invalidate("CACHE_CLEARED")
        warmupContext?.cancel("CACHE_CLEARED")
        if (cacheClearPending || cacheClearScheduled) return
        if (state.busy || generationGate.isClaimed()) {
            cacheClearPending = true
            return
        }
        cacheClearScheduled = true
        worker.execute {
            try {
                performCacheClear()
            } finally {
                cacheClearScheduled = false
            }
        }
    }

    private fun performCacheClear() {
        if (generationGate.isClaimed()) {
            cacheClearPending = true
            return
        }
        try {
            // Closing the runtime releases ORT sessions and all conditioning
            // caches before deleting files that can be regenerated.
            runtimes.values.forEach(BackendRuntime::close)
            synchronized(warmupLock) {
                completedWarmupKey = null
                queuedWarmupKey = null
            }
            val result = runtimeRepository.clearCaches()
            publishState(
                stage = GenerationStage.IDLE,
                busy = false,
                requestedBackend = state.requestedBackend,
                resolvedBackend = state.resolvedBackend,
                runtimeLabel = state.runtimeLabel,
                initializedModelId = null,
                latestOutputFilePath = null,
                progressPercent = null,
                progressLabel = null,
                generationDurationMs = null,
                lastCompletedAtMs = null,
                message = getString(
                    R.string.prototype_cache_cleared,
                    result.generatedAudioFiles,
                    result.derivedRuntimeFiles,
                ),
            )
        } catch (error: Throwable) {
            publishState(
                stage = GenerationStage.ERROR,
                busy = false,
                requestedBackend = state.requestedBackend,
                resolvedBackend = state.resolvedBackend,
                runtimeLabel = state.runtimeLabel,
                progressPercent = null,
                progressLabel = null,
                generationDurationMs = null,
                message = getString(
                    R.string.prototype_cache_clear_failed,
                    error.userMessage(resources) ?: error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun formatAudioDuration(audioSamples: Int, sampleRate: Int): String = String.format(
        Locale.US,
        "%.2fs",
        audioSamples.toDouble() / sampleRate,
    )

    // One state snapshot fans out to notification, bound UI listeners, and local API clients.
    private fun publishState(
        stage: GenerationStage,
        busy: Boolean,
        requestedBackend: ExecutionBackend,
        resolvedBackend: ExecutionBackend,
        runtimeLabel: String,
        message: String,
        initializedModelId: String? = state.initializedModelId,
        latestOutputFilePath: String? = state.latestOutputFilePath,
        progressPercent: Int? = null,
        progressLabel: String? = null,
        generationDurationMs: Long? = state.generationDurationMs,
        lastCompletedAtMs: Long? = state.lastCompletedAtMs,
        context: GenerationContext? = null,
        requestId: String? = context?.requestId,
        generationEpoch: Long = context?.generationEpoch ?: state.generationEpoch,
        errorCode: String? = null,
    ) {
        if (destroyed || (context != null && !generationGate.isCurrent(context))) return
        val snapshot = BackendServiceState(
            requestId = requestId,
            generationEpoch = generationEpoch,
            errorCode = errorCode,
            stage = stage,
            message = message,
            busy = busy,
            requestedBackend = requestedBackend,
            resolvedBackend = resolvedBackend,
            runtimeLabel = runtimeLabel,
            initializedModelId = initializedModelId,
            latestOutputFilePath = latestOutputFilePath,
            progressPercent = progressPercent,
            progressLabel = progressLabel,
            generationDurationMs = generationDurationMs,
            lastCompletedAtMs = lastCompletedAtMs,
        )
        state = snapshot
        updateNotification(snapshot)
        mainHandler.post {
            if (destroyed || state !== snapshot || (context != null && !generationGate.isCurrent(context))) return@post
            listeners.toList().forEach { it.onStateChanged(snapshot) }
            apiClients.toList().forEach { client ->
                runCatching {
                    client.send(
                        Message.obtain(null, BackendApiContract.MSG_STATE_CHANGED).apply {
                            data = snapshot.toBundle()
                        },
                    )
                }.onFailure {
                    apiClients -= client
                }
            }
        }
    }

    // Foreground service mode alone does not keep the CPU awake or boost this worker thread priority.
    private inline fun <T> withActiveGenerationResources(block: () -> T): T {
        val originalPriority = runCatching {
            Process.getThreadPriority(Process.myTid())
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        val wakeLock = acquireGenerationWakeLock()
        logPowerState()
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        try {
            return block()
        } finally {
            runCatching {
                Process.setThreadPriority(originalPriority)
            }
            if (wakeLock?.isHeld == true) {
                runCatching { wakeLock.release() }
            }
        }
    }

    private fun acquireGenerationWakeLock(): PowerManager.WakeLock? =
        runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:Generation")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(GENERATION_WAKE_LOCK_TIMEOUT_MS)
                }
        }.getOrNull()

    private fun logPowerState() {
        val powerManager = getSystemService(PowerManager::class.java)
        val powerSaveMode = powerManager?.isPowerSaveMode ?: false
        val idleMode = powerManager?.isDeviceIdleMode ?: false
        val ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        Log.i(
            TIMING_LOG_TAG,
            "power_state: powerSave=$powerSaveMode deviceIdle=$idleMode ignoringBatteryOptimizations=$ignoringBatteryOptimizations",
        )
    }

    private fun scheduleWarmup(defaults: ActiveDefaults) {
        if (destroyed) return
        val key = WarmupKey(defaults.backend, defaults.modelId)
        synchronized(warmupLock) {
            if (completedWarmupKey == key || queuedWarmupKey == key) return
            queuedWarmupKey = key
        }
        runCatching {
            worker.execute {
                try {
                    if (destroyed || activeDefaults?.let { WarmupKey(it.backend, it.modelId) } != key) return@execute
                    val model = modelRepository.discoverCharacterModels()
                        .firstOrNull { it.id == key.modelId }
                        ?: return@execute
                    if (MingModel.matches(model)) return@execute
                    val startedAt = SystemClock.elapsedRealtime()
                    val request = defaults.toWarmupRequest(model)
                    warmupContext = request.context
                    if (destroyed) request.context.cancel("SERVICE_DESTROYED")
                    withActiveGenerationResources {
                        request.context.run {
                            selectWorkerBackend(key.backend)
                            runtimes[key.backend]?.warmup(request)
                        }
                    }
                    synchronized(warmupLock) {
                        completedWarmupKey = key
                    }
                    Log.i(
                        TIMING_LOG_TAG,
                        "warmup completed backend=${key.backend.label} model=${key.modelId} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                } catch (error: Throwable) {
                    Log.i(TIMING_LOG_TAG, "warmup skipped: ${error.message}")
                } finally {
                    warmupContext = null
                    synchronized(warmupLock) {
                        if (queuedWarmupKey == key) queuedWarmupKey = null
                    }
                }
            }
        }.onFailure {
            synchronized(warmupLock) {
                if (queuedWarmupKey == key) queuedWarmupKey = null
            }
        }
    }

    private fun selectWorkerBackend(backend: ExecutionBackend) {
        if (workerBackend == backend) return
        workerBackend?.let { runtimes[it]?.close() }
        workerBackend = backend
        synchronized(warmupLock) { completedWarmupKey = null }
    }

    private fun ActiveDefaults.toWarmupRequest(model: CharacterModel): GenerationRequest =
        GenerationRequest(
            characterModel = model,
            language = language,
            promptLanguage = promptLanguage,
            synthesisText = if (language.startsWith("zh", ignoreCase = true)) "。" else ".",
            referenceAudioPath = referenceAudioPath,
            referenceText = referenceText,
            maxDecoderSteps = maxDecoderSteps,
            auxiliaryReferenceAudioPaths = auxiliaryReferenceAudioPaths,
        )

    private fun syncLocalHttpApiServer() {
        // Listening on a socket is idle work. Generation and warmup acquire
        // their own bounded wake lock in withActiveGenerationResources.
        if (settingsRepository.apiEnabled) {
            localHttpApiServer.start(settingsRepository.apiPort)
        } else {
            localHttpApiServer.stop()
        }
    }

    private fun stageMessage(
        stage: GenerationStage,
        characterModel: CharacterModel,
        messageKey: String,
    ): String = when (stage) {
        GenerationStage.INSPECTING_RESOURCES -> getString(R.string.prototype_stage_inspecting_resources)
        GenerationStage.INSTALLING_MODEL -> getString(R.string.prototype_stage_installing_model)
        GenerationStage.INITIALIZING_BACKEND -> getString(
            R.string.prototype_stage_initializing_backend,
            characterModel.displayName,
        )
        GenerationStage.PREPARING_FEATURES -> {
            if (messageKey == "preparing_runtime") {
                getString(R.string.prototype_stage_preparing_runtime)
            } else {
                getString(R.string.prototype_stage_preparing_features)
            }
        }
        GenerationStage.RUNNING_INFERENCE -> getString(R.string.prototype_stage_running_inference)
        GenerationStage.WRITING_OUTPUT -> getString(R.string.prototype_stage_writing_output)
        GenerationStage.COMPLETED -> getString(R.string.prototype_idle_status)
        GenerationStage.ERROR -> getString(R.string.prototype_generation_error, messageKey)
        GenerationStage.CANCELLED -> messageKey
        GenerationStage.IDLE -> getString(R.string.prototype_idle_status)
    }

    private fun updateNotification(state: BackendServiceState) {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: BackendServiceState) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.prototype_service_notification_title))
            .setContentText(state.message.ifBlank { getString(R.string.prototype_idle_status) })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    state.message.ifBlank { getString(R.string.prototype_idle_status) },
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.prototype_service_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.prototype_service_notification_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "voicebox_backend_service"
        private const val NOTIFICATION_ID = 2001
        private const val TIMING_LOG_TAG = "VoiceBoxTiming"
        private const val GENERATION_WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1_000L
        private const val KEY_PROGRESS_PERCENT = "progress_percent"
        private const val KEY_PROGRESS_LABEL = "progress_label"
        private const val KEY_GENERATION_DURATION_MS = "generation_duration_ms"
        private const val KEY_LAST_COMPLETED_AT_MS = "last_completed_at_ms"
        private const val ACTION_RELOAD_RESOURCES = "dev.renakoni.voicebox.action.RELOAD_RESOURCES"
        internal const val ACTION_RELOAD_INFERENCE_SETTINGS =
            "dev.renakoni.voicebox.action.RELOAD_INFERENCE_SETTINGS"
        private const val ACTION_CLEAR_CACHE = "dev.renakoni.voicebox.action.CLEAR_CACHE"

        fun serviceIntent(context: android.content.Context): Intent =
            Intent(context, VoiceBoxBackendService::class.java)
    }

    private inner class ApiHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                BackendApiContract.MSG_REGISTER_CLIENT -> {
                    message.replyTo?.let(apiClients::add)
                    sendStateTo(message.replyTo)
                }
                BackendApiContract.MSG_UNREGISTER_CLIENT -> {
                    message.replyTo?.let(apiClients::remove)
                }
                BackendApiContract.MSG_GET_STATUS -> {
                    sendStateTo(message.replyTo)
                }
                BackendApiContract.MSG_SYNTHESIZE -> {
                    val request = try { message.data.toGenerationRequest() } catch (error: IllegalArgumentException) {
                        sendErrorTo(message.replyTo, error.userMessage(resources) ?: error.message ?: getString(R.string.prototype_service_api_invalid_request))
                        return
                    } ?: run {
                        sendErrorTo(message.replyTo, getString(R.string.prototype_service_api_invalid_request))
                        return
                    }
                    val backend = message.data.getString(BackendApiContract.KEY_BACKEND)
                        ?.let { value -> ExecutionBackend.entries.firstOrNull { it.name == value } }
                        ?: ExecutionBackend.CPU
                    val started = synthesize(backend, request)
                    if (!started) {
                        sendErrorTo(message.replyTo, getString(R.string.prototype_generation_busy))
                    }
                }
                else -> super.handleMessage(message)
            }
        }
    }

    private fun sendStateTo(target: Messenger?) {
        if (target == null) {
            return
        }
        runCatching {
            target.send(
                Message.obtain(null, BackendApiContract.MSG_STATE_CHANGED).apply {
                    data = state.toBundle()
                },
            )
        }
    }

    private fun sendErrorTo(target: Messenger?, message: String) {
        if (target == null) {
            return
        }
        runCatching {
            target.send(
                Message.obtain(null, BackendApiContract.MSG_STATE_CHANGED).apply {
                    data = state.copy(
                        stage = GenerationStage.ERROR,
                        message = message,
                        busy = false,
                    ).toBundle()
                },
            )
        }
    }

    private fun Bundle.toGenerationRequest(): GenerationRequest? {
        val options = BackendApiContract.synthesisOptions(this)
        val modelId = getString(BackendApiContract.KEY_MODEL_ID) ?: return null
        val characterModel = modelRepository.discoverCharacterModels()
            .firstOrNull { it.id == modelId }
            ?: return null
        return GenerationRequest(
            characterModel = characterModel,
            synthesisOptions = options,
            language = getString(BackendApiContract.KEY_LANGUAGE).orEmpty(),
            promptLanguage = getString(BackendApiContract.KEY_PROMPT_LANGUAGE)
                ?.takeIf(String::isNotBlank)
                ?: getString(BackendApiContract.KEY_LANGUAGE).orEmpty(),
            synthesisText = getString(BackendApiContract.KEY_SYNTHESIS_TEXT).orEmpty(),
            referenceAudioPath = getString(BackendApiContract.KEY_REFERENCE_AUDIO_PATH).orEmpty(),
            referenceText = getString(BackendApiContract.KEY_REFERENCE_TEXT).orEmpty(),
            maxDecoderSteps = getInt(BackendApiContract.KEY_MAX_DECODER_STEPS, 500),
            auxiliaryReferenceAudioPaths = getStringArrayList(
                BackendApiContract.KEY_AUXILIARY_REFERENCE_AUDIO_PATHS,
            )?.toList().orEmpty(),
        )
    }

    private fun BackendServiceState.toBundle(): Bundle =
        Bundle().apply {
            putString("request_id", requestId)
            putLong("generation_epoch", generationEpoch)
            putString("error_code", errorCode)
            putString(BackendApiContract.KEY_STAGE, stage.name)
            putString(BackendApiContract.KEY_MESSAGE, message)
            putBoolean(BackendApiContract.KEY_BUSY, busy)
            putString(BackendApiContract.KEY_REQUESTED_BACKEND, requestedBackend.name)
            putString(BackendApiContract.KEY_RESOLVED_BACKEND, resolvedBackend.name)
            putString(BackendApiContract.KEY_RUNTIME_LABEL, runtimeLabel)
            putString(BackendApiContract.KEY_INITIALIZED_MODEL_ID, initializedModelId)
            putString(BackendApiContract.KEY_LATEST_OUTPUT_FILE_PATH, latestOutputFilePath)
            progressPercent?.let { putInt(KEY_PROGRESS_PERCENT, it) }
            putString(KEY_PROGRESS_LABEL, progressLabel)
            generationDurationMs?.let { putLong(KEY_GENERATION_DURATION_MS, it) }
            lastCompletedAtMs?.let { putLong(KEY_LAST_COMPLETED_AT_MS, it) }
        }
}
