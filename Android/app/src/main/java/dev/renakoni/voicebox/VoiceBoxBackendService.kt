package dev.renakoni.voicebox

import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.bridge.BackendApiContract
import dev.renakoni.voicebox.core.GenerationRequestGate

import dev.renakoni.voicebox.core.OfficialDownloadState
import dev.renakoni.voicebox.core.OutputStorage
import dev.renakoni.voicebox.bridge.LibraryAction
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.bridge.OfficialVoice
import dev.renakoni.voicebox.bridge.AccelerationInfo

import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.UiLanguageContext
import dev.renakoni.voicebox.core.i18n.userMessage
import dev.renakoni.voicebox.core.i18n.userText

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.renakoni.voicebox.api.ActiveDefaults
import dev.renakoni.voicebox.api.InferResult
import dev.renakoni.voicebox.api.LocalHttpApiServer
import dev.renakoni.voicebox.bridge.BackendRuntime
import dev.renakoni.voicebox.bridge.BackendRuntimeCallbacks
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.GenerationContext
import dev.renakoni.voicebox.bridge.GenerationCancelledException
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.BackendFailure
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BackendProbeResult(val modes: Set<ExecutionBackend>, val acceleration: AccelerationInfo?)

class VoiceBoxBackendService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))

    private val binder = LocalBinder()
    private val apiClients = linkedSetOf<Messenger>()
    private val listeners = linkedSetOf<BackendServiceListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val apiMessenger = Messenger(ApiHandler())
    private val generationGate = GenerationRequestGate()
    private val officialDownloadState = MutableStateFlow(OfficialDownloadState())
    val officialDownload = officialDownloadState.asStateFlow()
    internal val catalogReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile private var destroyed = false
    @Volatile private var warmupContext: GenerationContext? = null

    private data class WarmupKey(
        val backend: ExecutionBackend,
        val model: ModelRef,
        val referencePath: String, val transcript: String, val promptLanguage: String,
        val language: String, val auxiliary: List<String>,
    )

    private val ActiveDefaults.warmupKey get() = WarmupKey(backend, model, referenceAudioPath, referenceText,
        promptLanguage, language, auxiliaryReferenceAudioPaths)

    private val warmupLock = Any()
    private var queuedWarmupKey: WarmupKey? = null
    private var completedWarmupKey: WarmupKey? = null

    private lateinit var settingsRepository: AppSettingsRepository
    private val app get() = application as VoiceBoxApplication
    private val modelRepository get() = app.backends
    private lateinit var localHttpApiServer: LocalHttpApiServer

    // Mutated only on the serial inference worker.
    private var workerRuntime: BackendRuntime? = null
    private var runtimeOwner: String? = null
    private val enabledBackends = MutableStateFlow<Map<String, BackendProbeResult>>(emptyMap())
    internal val backendCapabilities = enabledBackends.asStateFlow()

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
        localHttpApiServer = LocalHttpApiServer(
            object : LocalHttpApiServer.Bridge {
                override fun currentState(): BackendServiceState = this@VoiceBoxBackendService.currentState()

                override fun availableModels(): List<CharacterModel> = modelRepository.discoverCharacterModels()

                override fun referenceExample(model: CharacterModel) = modelRepository.findReferenceExample(model)

                override fun supportsAuxiliaryReferences(model: CharacterModel): Boolean =
                    modelRepository.provider(model).supportsAuxiliaryReferences(model)

                override fun validateOptions(model: CharacterModel, options: Map<String, String>) =
                    modelRepository.provider(model).validateOptions(options)

                override fun activeDefaults(): ActiveDefaults? = this@VoiceBoxBackendService.activeDefaults

                override fun inferBlocking(backend: ExecutionBackend, request: GenerationRequest, timeoutMs: Long): InferResult =
                    this@VoiceBoxBackendService.inferBlocking(backend, request, timeoutMs)

                override fun cancel(requestId: String): Boolean = cancelGeneration(requestId)
            },
        )
        worker.execute {
            runCatching { app.bridgePackages.installBundled() }.onFailure { Log.e("VoiceBoxWorker", "Cannot preinstall backend", it) }
            modelRepository.providers.forEach { provider ->
                runCatching { provider.recoverInstallations() }.onFailure { Log.e("VoiceBoxWorker", "Cannot recover ${provider.id}", it) }
            }
            modelRepository.providers.forEach { if (!destroyed) refreshBackendModes(it) }
            catalogReady.complete(Unit)
            mainHandler.post { if (!destroyed) syncLocalHttpApiServer() }
        }
        createNotificationChannel()
        startForegroundCompat(NOTIFICATION_ID, buildNotification(state))
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
            closeWorkerRuntime()
            Log.i(TIMING_LOG_TAG, "runtime_closed_after_worker_return")
        }
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
                    workerRuntime?.trimMemory()
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

    private fun supportedBackends(model: CharacterModel) =
        model.executionModes intersect (enabledBackends.value[model.ref.backendId]?.modes ?: setOf(ExecutionBackend.CPU))

    fun setActiveDefaults(defaults: ActiveDefaults?, warmup: Boolean = true) {
        val supportedDefaults = defaults?.takeIf { it.backend in enabledBackends.value[it.model.backendId]?.modes.orEmpty() }
            ?: defaults?.copy(backend = ExecutionBackend.CPU)
        val previous = activeDefaults
        if (previous != null && previous.warmupKey != supportedDefaults?.warmupKey) {
            generationGate.invalidate("VOICE_CHANGED")
            warmupContext?.cancel("VOICE_CHANGED")
        }
        activeDefaults = supportedDefaults
        if (warmup) supportedDefaults?.let(::scheduleWarmup)
    }

    fun synthesize(
        backend: ExecutionBackend,
        request: GenerationRequest,
    ): Boolean = enqueue(backend, request) == null

    private fun enqueue(
        backend: ExecutionBackend?,
        request: GenerationRequest,
        onComplete: ((InferResult) -> Unit)? = null,
    ): InferResult.Rejected? {
        if (backend == null || backend !in supportedBackends(request.characterModel)) return InferResult.BackendUnavailable
        if (!generationGate.tryAcquire(request.context)) return InferResult.Busy
        try {
            worker.execute { runSynthesis(backend, request, onComplete) }
        } catch (error: Throwable) {
            generationGate.release(request.context)
            throw error
        }
        return null
    }

    fun inferBlocking(
        backend: ExecutionBackend,
        request: GenerationRequest,
        timeoutMs: Long,
    ): InferResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        val resultHolder = java.util.concurrent.atomic.AtomicReference<InferResult>(InferResult.Timeout)
        enqueue(backend, request) { result ->
            resultHolder.set(result)
            latch.countDown()
        }?.let { return it }
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

    private fun refreshBackendModes(provider: InstalledBackend) {
        val modes = provider.executionModes()
        enabledBackends.value += provider.id to BackendProbeResult(modes, provider.accelerationInfo())
    }

    fun downloadOfficialModel(voice: OfficialVoice): Boolean = downloadOnWorker(OfficialDownloadState(voice = voice.ref)) { context ->
        val library = modelRepository.provider(voice.ref.backendId)
        library.perform(LibraryAction.DOWNLOAD_VOICE, Bundle().apply { putString("model", voice.id); putString("variant", voice.ref.variant) }, context) { label, percent ->
            officialDownloadState.value = OfficialDownloadState(voice.ref, UiText.literal(label), percent, running = true)
        }
    }

    /** A null part installs the complete family. The bridge can also be installed independently. */
    fun installModelParts(id: String, part: String?): Boolean {
        val archive = app.bridgePackages.packages.singleOrNull { it.spec.id == id } ?: return false
        if (!archive.supported) return false
        val target = OfficialDownloadState(packageId = id)
        return downloadOnWorker(target) { context ->
            val provider = modelRepository.provider(id)
            val bridgeInstalled = archive.install(context::checkCancelled) { percent ->
                officialDownloadState.value = target.copy(label = UiText(R.string.model_bridge), percent = percent, running = true)
            }
            if (bridgeInstalled) refreshBackendModes(provider)
            if (part != BRIDGE_PART && provider.hasVoiceLibrary) {
                provider.perform(LibraryAction.INSTALL_RESOURCES, Bundle().apply { putString("pack", part) }, context) { label, percent ->
                    officialDownloadState.value = target.copy(label = UiText.literal(label), percent = percent, running = true)
                }
            }
        }
    }

    private fun downloadOnWorker(target: OfficialDownloadState, transfer: (GenerationContext) -> Unit): Boolean {
        return importOnWorker<Unit>(onComplete = { result ->
            officialDownloadState.value = result.fold(
                { target.copy(label = UiText(R.string.download_complete), completed = true) },
                { error ->
                    Log.w("OfficialModels", "Model download failed", error)
                    target.copy(error = if (error is java.util.concurrent.CancellationException)
                        UiText(R.string.download_cancelled) else error.userText()
                        ?: UiText(R.string.download_failed))
                })
        }) { context, _ ->
            officialDownloadState.value = target.copy(label = UiText(R.string.download_preparing), running = true)
            transfer(context)
        }
    }

    /** Import on the session owner thread; a replacement must never race inference or warmup. */
    fun importRuntimePackage(
        backendId: String, uri: android.net.Uri,
        onComplete: (Result<Unit>) -> Unit,
    ): Boolean = importOnWorker(onComplete) { context, _ ->
        modelRepository.provider(backendId).perform(LibraryAction.IMPORT_ARCHIVE,
            Bundle().apply { putString("uri", uri.toString()) }, context) { _, _ -> }
        Unit
    }

    /** The caller waits off the main thread while the inference owner switches to the import worker. */
    fun previewCheckpoint(backendId: String, uri: android.net.Uri): String? = worker.submit<String?> {
        closeWorkerRuntime()
        modelRepository.provider(backendId).perform(LibraryAction.PREVIEW_PAIR,
            Bundle().apply { putString("uri", uri.toString()) }, GenerationContext()) { _, _ -> }.getString("variant")
    }.get()

    fun importCheckpointPair(
        backendId: String, name: String, gpt: android.net.Uri, sovits: android.net.Uri, onProgress: (UiText) -> Unit,
        onComplete: (Result<ImportedVoice>) -> Unit, reference: ReferenceExample? = null,
        replace: ModelRef? = null, gptFilename: String = "", sovitsFilename: String = "",
    ): Boolean = importOnWorker(onComplete, onProgress) { context, progress ->
        val arguments = referenceArguments(name, reference).apply {
            putString("first", gpt.toString()); putString("second", sovits.toString()); putString("id", replace?.modelId)
            putString("firstName", gptFilename); putString("secondName", sovitsFilename); putString("variant", replace?.variant)
        }
        val provider = modelRepository.provider(backendId)
        require(replace == null || replace.backendId == backendId)
        val result = provider.perform(LibraryAction.IMPORT_PAIR, arguments, context) { text, _ -> progress(UiText.literal(text)) }
        ImportedVoice(ModelRef(provider.id, result.getString("variant")!!, result.getString("id")!!), result.getString("name")!!)
    }

    fun editCharacterReference(ref: ModelRef, name: String, reference: ReferenceExample,
        onComplete: (Result<ImportedVoice>) -> Unit): Boolean = importOnWorker(onComplete) { context, _ ->
        val args = referenceArguments(name, reference).apply { putString("id", ref.modelId); putString("variant", ref.variant) }
        modelRepository.provider(ref.backendId).perform(LibraryAction.EDIT_REFERENCE, args, context) { _, _ -> }
        ImportedVoice(ref, name.trim())
    }

    private fun referenceArguments(name: String, reference: ReferenceExample?) = Bundle().apply {
        putString("name", name)
        reference?.let { putString("reference", it.audioFile.absolutePath); putString("transcript", it.referenceText); putString("language", it.language) }
    }

    private fun <T> importOnWorker(
        onComplete: (Result<T>) -> Unit,
        onProgress: (UiText) -> Unit = {},
        operation: (GenerationContext, (UiText) -> Unit) -> T,
    ): Boolean {
        val context = GenerationContext()
        if (!generationGate.tryAcquire(context)) return false
        activeDefaults = null
        warmupContext?.cancel("RESOURCES_CHANGED")
        publishState(GenerationStage.INSTALLING_MODEL, true, state.requestedBackend,
            state.resolvedBackend, state.runtimeLabel, getString(R.string.resources_importing), context = context)
        worker.execute {
            val result = runCatching {
                context.checkCancelled()
                closeWorkerRuntime()
                synchronized(warmupLock) { completedWarmupKey = null; queuedWarmupKey = null }
                operation(context) { message -> mainHandler.post { onProgress(message) } }
            }
            generationGate.release(context)
            publishState(GenerationStage.IDLE, false, state.requestedBackend,
                state.resolvedBackend, state.runtimeLabel, "", initializedModelId = null)
            mainHandler.post { onComplete(result) }
            finishPendingMaintenance()
        }
        return true
    }

    private fun runSynthesis(
        backend: ExecutionBackend,
        request: GenerationRequest,
        onComplete: ((InferResult) -> Unit)? = null,
    ) {
        var runtime: BackendRuntime? = null
        val startedAtMs = SystemClock.elapsedRealtime()
        val context = request.context
        Log.i(TIMING_LOG_TAG, "request_id: ${context.requestId}")
        Log.i(TIMING_LOG_TAG, "generation_epoch: ${context.generationEpoch}")
        try {
            context.checkCancelled()
            val activeRuntime = selectWorkerRuntime(request.characterModel, backend)
            runtime = activeRuntime
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
            val inferenceRtf = result.inferenceSeconds?.let { it * result.sampleRate / result.audioSamples }
            val outputDuration = formatAudioDuration(result.audioSamples, result.sampleRate)

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
                    inferenceRtf = inferenceRtf,
                    lastCompletedAtMs = completedAtMs,
                    message = if (inferenceRtf == null) getString(R.string.prototype_service_completed, outputDuration)
                        else getString(R.string.prototype_service_completed_rtf, outputDuration, inferenceRtf),
                )
                onComplete?.invoke(InferResult.Success(result.file))
            }
            if (!delivered) {
                result.file.delete()
                throw GenerationCancelledException(context.cancellationReason ?: "STALE_GENERATION")
            }
        } catch (error: Throwable) {
            val cancelled = context.cancellationReason != null || error is GenerationCancelledException
            val errorCode = when {
                cancelled -> context.cancellationReason ?: "CANCELLED"
                error is BackendFailure -> error.code
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
            finishPendingMaintenance()
        }
    }

    private fun finishPendingMaintenance() {
        if (destroyed) return
        if (resourceReloadPending) reloadInferenceResources()
        if (cacheClearPending) {
            cacheClearPending = false
            performCacheClear()
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
        closeWorkerRuntime()
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
            closeWorkerRuntime()
            synchronized(warmupLock) {
                completedWarmupKey = null
                queuedWarmupKey = null
            }
            val generated = OutputStorage(requireNotNull(getExternalFilesDir(null))).clearGeneratedAudio()
            val derived = modelRepository.providers.filter { it.capabilities.imports.isNotEmpty() }.sumOf {
                it.perform(LibraryAction.CLEAR_CACHE, Bundle(), GenerationContext()) { _, _ -> }.getInt("derived")
            }
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
                    generated,
                    derived,
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
        inferenceRtf: Double? = null,
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
            inferenceRtf = inferenceRtf,
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
        if (!modelRepository.provider(defaults.model.backendId).supportsWarmup) return
        val key = defaults.warmupKey
        synchronized(warmupLock) {
            if (completedWarmupKey == key || queuedWarmupKey == key) return
            queuedWarmupKey = key
        }
        runCatching {
            worker.execute {
                try {
                    if (destroyed || activeDefaults?.warmupKey != key) return@execute
                    val model = modelRepository.discoverCharacterModels()
                        .firstOrNull { it.ref == key.model }
                        ?: return@execute
                    val startedAt = SystemClock.elapsedRealtime()
                    val request = defaults.toWarmupRequest(model)
                    warmupContext = request.context
                    if (destroyed || activeDefaults?.warmupKey != key) return@execute
                    withActiveGenerationResources {
                        request.context.run {
                            selectWorkerRuntime(model, key.backend).warmup(request)
                        }
                    }
                    synchronized(warmupLock) {
                        completedWarmupKey = key
                    }
                    Log.i(
                        TIMING_LOG_TAG,
                        "warmup completed backend=${key.backend.label} model=${key.model} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
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

    private fun selectWorkerRuntime(model: CharacterModel, backend: ExecutionBackend): BackendRuntime {
        val provider = modelRepository.provider(model)
        workerRuntime?.let { if (runtimeOwner == provider.id && it.backend == backend) return it }
        closeWorkerRuntime()
        return provider.createRuntime(backend).also { workerRuntime = it; runtimeOwner = provider.id }
    }

    private fun closeWorkerRuntime() {
        val previous = workerRuntime
        workerRuntime = null
        runtimeOwner = null
        synchronized(warmupLock) { completedWarmupKey = null }
        previous?.close()
    }

    private fun ActiveDefaults.toWarmupRequest(model: CharacterModel): GenerationRequest =
        GenerationRequest(
            characterModel = model,
            language = language,
            promptLanguage = promptLanguage,
            synthesisText = if (language.startsWith("zh", ignoreCase = true)) "。" else ".",
            referenceAudioPath = referenceAudioPath,
            referenceText = referenceText,
            options = options,
            auxiliaryReferenceAudioPaths = auxiliaryReferenceAudioPaths,
        )

    private fun syncLocalHttpApiServer() {
        if (!catalogReady.isCompleted) return
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
    ): String = when {
        messageKey == "preparing_runtime" -> getString(R.string.prototype_stage_preparing_runtime)
        messageKey.isNotBlank() && messageKey != stage.name.lowercase(java.util.Locale.ROOT) -> messageKey
        else -> when (stage) {
            GenerationStage.INSPECTING_RESOURCES -> getString(R.string.prototype_stage_inspecting_resources)
            GenerationStage.INSTALLING_MODEL -> getString(R.string.prototype_stage_installing_model)
            GenerationStage.INITIALIZING_BACKEND -> getString(
                R.string.prototype_stage_initializing_backend,
                characterModel.displayName,
            )
            GenerationStage.PREPARING_FEATURES -> getString(R.string.prototype_stage_preparing_features)
            GenerationStage.RUNNING_INFERENCE -> getString(R.string.prototype_stage_running_inference)
            GenerationStage.WRITING_OUTPUT -> getString(R.string.prototype_stage_writing_output)
            GenerationStage.COMPLETED -> getString(R.string.prototype_idle_status)
            GenerationStage.ERROR -> getString(R.string.prototype_generation_error, messageKey)
            GenerationStage.CANCELLED -> messageKey
            GenerationStage.IDLE -> getString(R.string.prototype_idle_status)
        }
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
        const val BRIDGE_PART = "bridge"
        // A restarted service shares the queue with the previous instance until its native worker has exited.
        private val worker = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue<Runnable>())
        private val COMMON_REQUEST_KEYS = with(BackendApiContract) { setOf(KEY_MODEL_ID, KEY_BACKEND_ID, KEY_VARIANT, KEY_LANGUAGE,
            KEY_PROMPT_LANGUAGE, KEY_SYNTHESIS_TEXT, KEY_REFERENCE_AUDIO_PATH, KEY_REFERENCE_TEXT,
            KEY_AUXILIARY_REFERENCE_AUDIO_PATHS, KEY_BACKEND) }
        private const val NOTIFICATION_CHANNEL_ID = "voicebox_backend_service"
        private const val NOTIFICATION_ID = 2001
        private const val TIMING_LOG_TAG = "VoiceBoxTiming"
        private const val GENERATION_WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1_000L
        private const val KEY_PROGRESS_PERCENT = "progress_percent"
        private const val KEY_PROGRESS_LABEL = "progress_label"
        private const val KEY_GENERATION_DURATION_MS = "generation_duration_ms"
        private const val KEY_INFERENCE_RTF = "inference_rtf"
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
                    if (destroyed) return
                    if (!catalogReady.isCompleted) {
                        val pending = Message.obtain(message)
                        worker.execute { post { if (destroyed) pending.recycle() else sendMessage(pending) } }
                        return
                    }
                    val request = try { message.data.toGenerationRequest() } catch (error: IllegalArgumentException) {
                        sendErrorTo(message.replyTo, error.userMessage(resources) ?: error.message ?: getString(R.string.prototype_service_api_invalid_request))
                        return
                    } ?: run {
                        sendErrorTo(message.replyTo, getString(R.string.prototype_service_api_invalid_request))
                        return
                    }
                    val requested = message.data.getString(BackendApiContract.KEY_BACKEND) ?: ExecutionBackend.CPU.name
                    val backend = ExecutionBackend.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }
                    when (enqueue(backend, request)) {
                        InferResult.BackendUnavailable -> sendErrorTo(message.replyTo, getString(R.string.prototype_backend_unavailable, requested))
                        InferResult.Busy -> sendErrorTo(message.replyTo, getString(R.string.prototype_generation_busy))
                        null -> Unit
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
        val modelId = getString(BackendApiContract.KEY_MODEL_ID) ?: return null
        val backendId = getString(BackendApiContract.KEY_BACKEND_ID)
        val variant = getString(BackendApiContract.KEY_VARIANT)
        val characterModel = modelRepository.discoverCharacterModels()
            .singleOrNull { it.id == modelId && (backendId == null || it.ref.backendId == backendId) &&
                (variant == null || it.ref.variant == variant) }
            ?: return null
        return GenerationRequest(
            characterModel = characterModel,
            options = modelRepository.provider(characterModel).validateOptions(buildMap {
                for (key in keySet() - COMMON_REQUEST_KEYS) {
                    @Suppress("DEPRECATION")
                    val value = this@toGenerationRequest.get(key)
                    require(value is Number || value is String) { "$key must be a scalar." }
                    put(key, value.toString())
                }
            }),
            language = getString(BackendApiContract.KEY_LANGUAGE).orEmpty(),
            promptLanguage = getString(BackendApiContract.KEY_PROMPT_LANGUAGE)
                ?.takeIf(String::isNotBlank)
                ?: getString(BackendApiContract.KEY_LANGUAGE).orEmpty(),
            synthesisText = getString(BackendApiContract.KEY_SYNTHESIS_TEXT).orEmpty(),
            referenceAudioPath = getString(BackendApiContract.KEY_REFERENCE_AUDIO_PATH).orEmpty(),
            referenceText = getString(BackendApiContract.KEY_REFERENCE_TEXT).orEmpty(),
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
            inferenceRtf?.let { putDouble(KEY_INFERENCE_RTF, it) }
            lastCompletedAtMs?.let { putLong(KEY_LAST_COMPLETED_AT_MS, it) }
        }
}

data class ImportedVoice(val ref: ModelRef, val displayName: String)
