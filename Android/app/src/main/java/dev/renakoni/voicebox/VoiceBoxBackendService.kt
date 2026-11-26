package dev.renakoni.voicebox

import dev.renakoni.voicebox.core.DeviceFacts
import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.core.GenerationRequestGate

import dev.renakoni.voicebox.core.OfficialDownloadState
import dev.renakoni.voicebox.core.deleteFileTree
import dev.renakoni.voicebox.bridge.LibraryAction
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.bridge.OfficialVoice

import dev.renakoni.voicebox.core.i18n.LocalizedFailure
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.UiLanguageContext
import dev.renakoni.voicebox.core.i18n.checkUi
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


class VoiceBoxBackendService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))

    private val binder = LocalBinder()
    private val listeners = linkedSetOf<BackendServiceListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generationGate = GenerationRequestGate()
    private val officialDownloadState = MutableStateFlow(OfficialDownloadState())
    val officialDownload = officialDownloadState.asStateFlow()
    internal val catalogReady = CompletableDeferred<Unit>()
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
    private val enabledBackends = MutableStateFlow<Map<String, Set<ExecutionBackend>>>(emptyMap())
    internal val backendCapabilities = enabledBackends.asStateFlow()

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

                override fun availableModels(): List<CharacterModel> {
                    val enabled = enabledBackends.value
                    return app.installedVoiceModels(modelRepository.providers.filter { provider ->
                        enabled[provider.id].orEmpty().isNotEmpty() && (provider.id != QwenModelPackage.BACKEND ||
                            app.qwenModelPackage.pack(QwenModelPackage.CODEC) {}.installed)
                    })
                }

                override fun referenceExample(model: CharacterModel) = modelRepository.findReferenceExample(model)

                override fun supportsAuxiliaryReferences(model: CharacterModel): Boolean =
                    modelRepository.provider(model).supportsAuxiliaryReferences(model)

                override fun resolveSynthesisOptions(model: CharacterModel, defaults: Map<String, String>, overrides: Map<String, String>) =
                    modelRepository.provider(model).resolveSynthesisOptions(defaults, overrides)
                override fun usesReference(model: CharacterModel, options: Map<String, String>) =
                    modelRepository.provider(model).usesReference(options)
                override fun referenceInput(model: CharacterModel) = modelRepository.provider(model).referenceInput(model)
                override fun requiresOutputLanguage(model: CharacterModel, options: Map<String, String>) =
                    modelRepository.provider(model).let { it.requiresOutputLanguage && it.usesOutputLanguage(options) }
                override fun languageError(model: CharacterModel, language: String, text: String) =
                    modelRepository.provider(model).languageError(language, text)?.let { UiText(it).resolve(resources) }

                override fun activeDefaults(): ActiveDefaults? = this@VoiceBoxBackendService.activeDefaults

                override fun inferBlocking(backend: ExecutionBackend, request: GenerationRequest, timeoutMs: Long): InferResult =
                    this@VoiceBoxBackendService.inferBlocking(backend, request, timeoutMs)

                override fun cancel(requestId: String): Boolean = cancelGeneration(requestId)
            },
        )
        worker.execute {
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
        if (intent?.action == ACTION_RELOAD_INFERENCE_SETTINGS) requestResourceReload()
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        destroyed = true
        generationGate.stop()
        warmupContext?.cancel("SERVICE_DESTROYED")
        localHttpApiServer.stop()
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
        model.executionModes intersect enabledBackends.value[model.ref.backendId].orEmpty()

    fun setActiveDefaults(defaults: ActiveDefaults?, warmup: Boolean = true) {
        val supportedDefaults = defaults?.takeIf { it.backend in enabledBackends.value[it.model.backendId].orEmpty() }
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
        onComplete: (InferResult) -> Unit,
    ): Boolean = enqueue(backend, request) { result ->
        mainHandler.post { onComplete(result) }
    } == null

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

    private var pendingGpuCheck: CompletableDeferred<DeviceFacts.GpuRecheck>? = null

    @Synchronized internal fun recheckDeviceGpu(): Deferred<DeviceFacts.GpuRecheck> {
        pendingGpuCheck?.takeIf { it.isActive }?.let { return it }
        val result = CompletableDeferred<DeviceFacts.GpuRecheck>()
        pendingGpuCheck = result
        worker.execute {
            try {
                val check = DeviceFacts.recheckVulkan()
                if (check.changed && !destroyed) modelRepository.providers.forEach(::refreshBackendModes)
                result.complete(check)
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    private fun refreshBackendModes(provider: InstalledBackend) {
        enabledBackends.value += provider.id to provider.candidateModes
    }

    fun downloadOfficialModel(voice: OfficialVoice): Boolean = downloadOnWorker(OfficialDownloadState(voice = voice.ref)) { context, progress ->
        checkUi(app.bridgePackages.get(voice.ref.backendId).installed) { UiText(R.string.shared_resources_missing) }
        if (voice.ref.backendId == QwenModelPackage.BACKEND) {
            app.qwenModelPackage.install(QwenModelPackage.TALKER, context::checkCancelled) { percent ->
                progress(UiText.literal(voice.name), percent)
            }
            return@downloadOnWorker
        }
        val library = modelRepository.provider(voice.ref.backendId)
        library.perform(LibraryAction.DOWNLOAD_VOICE, Bundle().apply { putString("model", voice.id); putString("variant", voice.ref.variant) }, context) { label, percent ->
            progress(UiText.literal(label), percent)
        }
    }

    internal fun removeOfficialWeights(voice: OfficialVoice, onComplete: (Result<Unit>) -> Unit): Boolean =
        removeOnWorker(OfficialDownloadState(voice = voice.ref), onComplete) {
            val ref = voice.ref
            if (ref.backendId == QwenModelPackage.BACKEND) {
                app.qwenModelPackage.uninstall(QwenModelPackage.TALKER)
                return@removeOnWorker
            }
            require(ref.backendId == "gptsovits")
            val provider = modelRepository.provider(ref.backendId)
            require(provider.officialVoices().any { it.ref == ref })
            val root = requireNotNull(getExternalFilesDir(null)).canonicalFile
            val weights = File(root, "CharacterModels/${ref.variant}/${ref.modelId}/tts_models")
            require(weights.parentFile!!.canonicalFile == weights.parentFile)
            deleteFileTree(weights)
        }

    /** A null part installs the complete family. The bridge can also be installed independently. */
    fun installModelParts(id: String, part: String?): Boolean {
        val archive = app.bridgePackages.packages.singleOrNull { it.spec.id == id } ?: return false
        if (!archive.installable) return false
        val target = OfficialDownloadState(packageId = id)
        return downloadOnWorker(target) { context, progress ->
            val provider = modelRepository.provider(id)
            val codecOnly = id == QwenModelPackage.BACKEND && part == QwenModelPackage.CODEC
            val bridgeInstalled = !codecOnly && archive.install(context::checkCancelled) { percent ->
                progress(UiText(R.string.model_bridge), percent)
            }
            if (bridgeInstalled) {
                provider.invalidateMetadata()
                refreshBackendModes(provider)
            }
            if (part != BRIDGE_PART && id == QwenModelPackage.BACKEND) {
                require(part == null || part == QwenModelPackage.CODEC)
                app.qwenModelPackage.install(QwenModelPackage.CODEC, context::checkCancelled) { percent ->
                    progress(UiText(R.string.qwen_audio_codec), percent)
                }
            } else if (part != BRIDGE_PART && id == MingModelPackage.BACKEND) {
                val model = app.mingModelPackage
                require(part == null || part in model.modelParts)
                val parts = part?.let(::listOf) ?: model.modelParts
                parts.forEach { component ->
                    val label = UiText(MingModelPackage.partTitle(component))
                    model.install(component, context::checkCancelled) { percent ->
                        progress(label, percent)
                    }
                }
            } else if (part != BRIDGE_PART && provider.hasVoiceLibrary) {
                provider.perform(LibraryAction.INSTALL_RESOURCES, Bundle().apply { putString("pack", part) }, context) { label, percent ->
                    progress(UiText.literal(label), percent)
                }
            }
        }
    }

    fun removeModelPart(id: String, part: String, onComplete: (Result<Unit>) -> Unit): Boolean {
        val archive = app.bridgePackages.packages.singleOrNull { it.spec.id == id } ?: return false
        val target = if (id == MingModelPackage.BACKEND && part == MingModelPackage.OFFICIAL) {
            OfficialDownloadState(voice = MingModelPackage.modelRef)
        } else OfficialDownloadState(packageId = id)
        return removeOnWorker(target, onComplete) { context ->
            val provider = modelRepository.provider(id)
            if (part == BRIDGE_PART) {
                try {
                    archive.uninstall()
                } finally {
                    provider.invalidateMetadata()
                    refreshBackendModes(provider)
                }
            } else if (id == QwenModelPackage.BACKEND) {
                require(part == QwenModelPackage.CODEC || part == QwenModelPackage.TALKER)
                app.qwenModelPackage.uninstall(part)
            } else if (id == MingModelPackage.BACKEND) {
                app.mingModelPackage.uninstall(part)
            } else {
                provider.perform(LibraryAction.REMOVE_RESOURCES, Bundle().apply { putString("pack", part) },
                    context) { _, _ -> }
            }
        }
    }

    private fun removeOnWorker(target: OfficialDownloadState, onComplete: (Result<Unit>) -> Unit,
        remove: (GenerationContext) -> Unit): Boolean = importOnWorker(onComplete = { result ->
        val download = officialDownloadState.value
        if (result.isSuccess && download.finished && download.voice == target.voice && download.packageId == target.packageId) {
            officialDownloadState.value = OfficialDownloadState()
        }
        onComplete(result)
    }, message = R.string.model_removing) { context, _ -> remove(context) }

    fun downloadMingVoices(): Boolean {
        val archive = app.bridgePackages.get(MingModelPackage.BACKEND)
        if (!archive.installed) return false
        val target = OfficialDownloadState(voice = MingModelPackage.modelRef)
        return downloadOnWorker(target) { context, progress ->
            app.mingModelPackage.install(MingModelPackage.OFFICIAL, context::checkCancelled) { percent ->
                progress(UiText(R.string.ming_official_weights), percent)
            }
        }
    }

    fun cancelOfficialDownload(requestId: String): Boolean {
        val active = officialDownloadState.value
        if (active.requestId != requestId || !active.running || !cancelGeneration(requestId)) return false
        officialDownloadState.value = active.copy(running = false, label = null, percent = null)
        return true
    }

    private fun downloadOnWorker(target: OfficialDownloadState,
        transfer: (GenerationContext, (UiText, Int?) -> Unit) -> Unit): Boolean {
        val context = GenerationContext()
        val active = target.copy(requestId = context.requestId, running = true, label = UiText(R.string.download_preparing))
        val accepted = importOnWorker<Unit>(context = context, message = R.string.download_preparing, onComplete = { result ->
            if (officialDownloadState.value.requestId != context.requestId) return@importOnWorker
            val error = result.exceptionOrNull()?.takeUnless {
                context.cancellationReason != null || it is java.util.concurrent.CancellationException
            }
            if (error != null) Log.w("OfficialModels", "Model download failed", error)
            val finished = target.copy(requestId = context.requestId, finished = true,
                label = if (result.isSuccess) UiText(R.string.download_complete) else null,
                error = error?.let { it.userText() ?: UiText(R.string.download_failed) })
            officialDownloadState.value = finished
            mainHandler.postDelayed({ officialDownloadState.compareAndSet(finished, OfficialDownloadState()) }, 4_000L)
        }) { _, _ ->
            transfer(context) { label, percent ->
                mainHandler.post {
                    val current = officialDownloadState.value
                    if (current.requestId == context.requestId && current.running)
                        officialDownloadState.value = active.copy(label = label, percent = percent)
                }
            }
        }
        if (accepted) officialDownloadState.value = active
        return accepted
    }

    /** Import on the session owner thread; a replacement must never race inference or warmup. */
    fun importRuntimePackage(
        backendId: String, uri: android.net.Uri,
        onComplete: (Result<Unit>) -> Unit,
    ): Boolean = importOnWorker(onComplete) { context, _ ->
        modelRepository.provider(backendId).perform(LibraryAction.IMPORT_ARCHIVE,
            Bundle().apply { putString("uri", uri.toString()) }, context) { _, _ -> }
    }

    /** The caller waits off the main thread while the inference owner switches to the import worker. */
    fun previewCheckpoint(backendId: String, uri: android.net.Uri): String? = worker.submit<String?> {
        closeWorkerRuntime()
        modelRepository.provider(backendId).perform(LibraryAction.PREVIEW_PAIR,
            Bundle().apply { putString("uri", uri.toString()) }, GenerationContext()) { _, _ -> }.getString("variant")
    }.get()

    fun importVoiceCheckpoint(name: String, source: VoiceCheckpointSource, onProgress: (UiText) -> Unit,
        onComplete: (Result<ImportedVoice>) -> Unit): Boolean = importOnWorker(onComplete, onProgress) { context, progress ->
        val provider = modelRepository.provider(source.backendId)
        require(source.format in provider.capabilities.imports) { "Install the ${source.backendId} checkpoint importer" }
        val result = provider.perform(source.action, source.arguments(name), context) { text, _ -> progress(UiText.literal(text)) }
        ImportedVoice(ModelRef(provider.id, result.getString("variant")!!, result.getString("id")!!), result.getString("name")!!)
    }

    fun editCharacterReference(ref: ModelRef, name: String, reference: ReferenceExample,
        onComplete: (Result<ImportedVoice>) -> Unit): Boolean = importOnWorker(onComplete) { context, _ ->
        val args = referenceArguments(name, reference).apply { putString("id", ref.modelId); putString("variant", ref.variant) }
        modelRepository.provider(ref.backendId).perform(LibraryAction.EDIT_REFERENCE, args, context) { _, _ -> }
        ImportedVoice(ref, name.trim())
    }

    private fun <T> importOnWorker(
        onComplete: (Result<T>) -> Unit,
        onProgress: (UiText) -> Unit = {},
        message: Int = R.string.resources_importing,
        context: GenerationContext = GenerationContext(),
        operation: (GenerationContext, (UiText) -> Unit) -> T,
    ): Boolean {
        if (!generationGate.tryAcquire(context)) return false
        activeDefaults = null
        warmupContext?.cancel("RESOURCES_CHANGED")
        publishState(GenerationStage.INSTALLING_MODEL, true, state.requestedBackend,
            state.resolvedBackend, state.runtimeLabel, getString(message), context = context)
        worker.execute {
            val result = runCatching {
                context.checkCancelled()
                closeWorkerRuntime()
                context.run { operation(context) { message -> mainHandler.post { onProgress(message) } } }
            }
            generationGate.release(context)
            publishState(GenerationStage.IDLE, false, state.requestedBackend,
                state.resolvedBackend, state.runtimeLabel, "", initializedModelId = null)
            mainHandler.post { onComplete(result) }
        }
        return true
    }

    private class UnsupportedBackend : BackendFailure("BACKEND_UNAVAILABLE", "Backend unavailable"), LocalizedFailure {
        override val text = UiText(R.string.model_device_unsupported)
    }

    private fun runSynthesis(
        backend: ExecutionBackend,
        request: GenerationRequest,
        onComplete: ((InferResult) -> Unit)? = null,
    ) {
        var runtime: BackendRuntime? = null
        var measuredModes: Set<ExecutionBackend>? = null
        val startedAtMs = SystemClock.elapsedRealtime()
        val context = request.context
        Log.i(TIMING_LOG_TAG, "request_id: ${context.requestId}")
        Log.i(TIMING_LOG_TAG, "generation_epoch: ${context.generationEpoch}")
        try {
            val provider = modelRepository.provider(request.characterModel)
            val activeRuntime = context.run {
                selectWorkerRuntime(provider, backend) {
                    val modes = provider.executionModes()
                    measuredModes = modes
                    if (backend !in modes) throw UnsupportedBackend()
                    provider.createRuntime(backend)
                }
            }
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
            val audioDurationMs = result.audioSamples * 1000L / result.sampleRate

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
                    latestOutputDurationMs = audioDurationMs,
                    progressPercent = 100,
                    progressLabel = null,
                    generationDurationMs = durationMs,
                    inferenceRtf = inferenceRtf,
                    lastCompletedAtMs = completedAtMs,
                    message = if (inferenceRtf == null) getString(R.string.prototype_service_completed, outputDuration)
                        else getString(R.string.prototype_service_completed_rtf, outputDuration, inferenceRtf),
                )
                onComplete?.invoke(InferResult.Success(result.file, audioDurationMs))
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
                message = if (cancelled) getString(R.string.generation_cancelled)
                    else error.userMessage(resources) ?: getString(R.string.generation_failed),
            )
            onComplete?.invoke(if (!cancelled && error is UnsupportedBackend) InferResult.BackendUnavailable
                else InferResult.Error(error.userMessage(resources) ?: error.message ?: error.javaClass.simpleName, errorCode))
        } finally {
            measuredModes?.let { enabledBackends.value += request.characterModel.ref.backendId to it }
            Log.i(TIMING_LOG_TAG, "request_total_ms=${SystemClock.elapsedRealtime() - startedAtMs} ms")
        }
    }

    private fun requestResourceReload() {
        generationGate.invalidate("RESOURCES_CHANGED")
        warmupContext?.cancel("RESOURCES_CHANGED")
        worker.execute {
            if (destroyed) return@execute
            closeWorkerRuntime()
            activeDefaults?.let(::scheduleWarmup)
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
        latestOutputDurationMs: Long = state.latestOutputDurationMs,
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
            latestOutputDurationMs = if (latestOutputFilePath == null) 0 else latestOutputDurationMs,
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
        val provider = modelRepository.provider(defaults.model.backendId)
        if (!provider.supportsWarmup) return
        val key = defaults.warmupKey
        synchronized(warmupLock) {
            if (completedWarmupKey == key || queuedWarmupKey == key) return
            queuedWarmupKey = key
        }
        runCatching {
            worker.execute {
                try {
                    if (destroyed || activeDefaults?.warmupKey != key) return@execute
                    val prepared = provider.createWarmupRuntime(key.backend) ?: return@execute
                    val model = modelRepository.discoverCharacterModels()
                        .firstOrNull { it.ref == key.model }
                        ?: return@execute
                    val startedAt = SystemClock.elapsedRealtime()
                    val request = defaults.toWarmupRequest(model)
                    warmupContext = request.context
                    if (destroyed || activeDefaults?.warmupKey != key) return@execute
                    withActiveGenerationResources {
                        request.context.run {
                            selectWorkerRuntime(provider, key.backend) { prepared }.warmup(request)
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

    private fun selectWorkerRuntime(provider: InstalledBackend, backend: ExecutionBackend, create: () -> BackendRuntime): BackendRuntime {
        workerRuntime?.let { if (runtimeOwner == provider.id && it.backend == backend) return it }
        closeWorkerRuntime()
        return create().also { workerRuntime = it; runtimeOwner = provider.id }
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
        private const val NOTIFICATION_CHANNEL_ID = "voicebox_backend_service"
        private const val NOTIFICATION_ID = 2001
        private const val TIMING_LOG_TAG = "VoiceBoxTiming"
        private const val GENERATION_WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1_000L
        internal const val ACTION_RELOAD_INFERENCE_SETTINGS =
            "dev.renakoni.voicebox.action.RELOAD_INFERENCE_SETTINGS"

        fun serviceIntent(context: android.content.Context): Intent =
            Intent(context, VoiceBoxBackendService::class.java)
    }
}

data class ImportedVoice(val ref: ModelRef, val displayName: String)
