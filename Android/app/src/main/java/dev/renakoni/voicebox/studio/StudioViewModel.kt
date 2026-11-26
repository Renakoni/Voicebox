package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.bridge.OfficialVoice
import dev.renakoni.voicebox.bridge.ResourcePack
import dev.renakoni.voicebox.bridge.VoiceImport
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.bridge.ReferenceInput
import dev.renakoni.voicebox.VoiceCheckpointSource
import dev.renakoni.voicebox.ImportedVoice
import dev.renakoni.voicebox.core.BridgeInstallState
import dev.renakoni.voicebox.core.OfficialDownloadState
import dev.renakoni.voicebox.VoiceBoxApplication

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.MingModelPackage
import dev.renakoni.voicebox.QwenModelPackage
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.userText
import dev.renakoni.voicebox.core.i18n.requireUi
import dev.renakoni.voicebox.core.i18n.errorUi

import android.app.Application
import android.content.res.Resources
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.renakoni.voicebox.AppSettingsRepository
import dev.renakoni.voicebox.BackendServiceListener
import dev.renakoni.voicebox.BackendServiceState
import dev.renakoni.voicebox.VoiceBoxBackendService
import dev.renakoni.voicebox.ReferenceAudioPicker
import dev.renakoni.voicebox.api.ActiveDefaults
import dev.renakoni.voicebox.api.InferResult
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.BackendCapabilities
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationContext
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.core.ReferenceLanguage
import dev.renakoni.voicebox.studio.creation.CreationSettings
import dev.renakoni.voicebox.core.DeviceFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

data class VoiceDraft(val id: String = UUID.randomUUID().toString(), val name: String = "",
    val modelRef: ModelRef? = null, val audioPath: String = "", val audioName: String = "",
    val transcript: String = "", val durationMs: Long = 0, val referenceLanguage: String = "auto", val avatarPath: String = "")

internal enum class CheckpointFormat(val capability: VoiceImport, val title: String, val firstLabel: Int, val secondLabel: Int,
    val firstExtension: String, val secondExtension: String) {
    GPT(VoiceImport.CHECKPOINT_PAIR, "GPT-SoVITS", R.string.gpt_weights, R.string.sovits_weights, ".ckpt", ".pth"),
    QWEN(VoiceImport.QWEN_SAFETENSORS, "Qwen3-TTS", R.string.qwen_config, R.string.qwen_checkpoint_weights, ".json", ".safetensors"),
}

internal data class CheckpointDraft(val format: CheckpointFormat, val name: String = "", val first: Uri? = null, val second: Uri? = null,
    val firstName: String = "", val secondName: String = "", val audioPath: String = "",
    val audioName: String = "", val transcript: String = "", val durationMs: Long = 0, val referenceLanguage: String = "auto",
    val editingRef: ModelRef? = null, val avatarPath: String = "")

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceBoxApplication
    private val store = StudioStore(app)
    private val avatars = VoiceAvatarStore(app)
    private val modelsRepository = app.backends
    private val settings = AppSettingsRepository(app)
    val backendPackages = app.bridgePackages.packages.sortedBy { it.spec.displayName }
    var bridgeStates by mutableStateOf<Map<String, BridgeInstallState>?>(null); private set
    var resourcePacks by mutableStateOf<Map<String, List<ResourcePack>>>(emptyMap()); private set
    internal var mingVoiceWeights by mutableStateOf<ResourcePack?>(null); private set
    internal var qwenVoiceWeights by mutableStateOf<ResourcePack?>(null); private set
    internal val mingName get() = backendPackages.first { it.spec.id == MingModelPackage.BACKEND }.spec.displayName
    private var capabilities by mutableStateOf(emptyMap<String, BackendCapabilities>())
    private val selectedCapabilities get() = capabilities[selectedVoice?.model?.backendId]
    var officialModels by mutableStateOf(emptyList<OfficialVoice>()); private set
    val needsModel get() = bridgeStates?.containsValue(BridgeInstallState.CURRENT) != true
    private fun providerFor(type: VoiceImport) = capabilities.entries.firstOrNull { type in it.value.imports }?.key
    internal val checkpointFormats get() = CheckpointFormat.entries.filter { providerFor(it.capability) != null }
    private val importProvider get() = checkpointDraft?.let { it.editingRef?.backendId ?: providerFor(it.format.capability) }
        ?.let(modelsRepository::provider)
    fun variantLabel(ref: ModelRef): UiText {
        val key = modelsRepository.provider(ref.backendId).variantLabel(ref.variant)
        return if (key.isEmpty()) UiText.literal(ref.variant) else UiText(key)
    }
    private val archiveProvider get() = selectedVoice?.let {
        it.model.backendId.takeIf { VoiceImport.RUNTIME_ARCHIVE in selectedCapabilities?.imports.orEmpty() }
    } ?: providerFor(VoiceImport.RUNTIME_ARCHIVE)?.takeIf { selectedVoice == null }
    val canImportArchive get() = archiveProvider != null
    val resourceHint get() = selectedVoice?.model?.let { modelsRepository.provider(it.backendId).resourceHint(it.variant) }

    val importLanguages get() = importProvider?.capabilities?.languages.orEmpty()
    fun languages(ref: ModelRef?) = capabilities[ref?.backendId]?.languages.orEmpty()
    private var referenceInputs by mutableStateOf(emptyMap<ModelRef, ReferenceInput>())
    fun optionalReference(ref: ModelRef?) = referenceInputs[ref] == ReferenceInput.OPTIONAL
    fun acceptsReference(ref: ModelRef) = referenceInputs[ref] != ReferenceInput.NONE
    val cloneModels get() = models.filter { acceptsReference(it.ref) }
    private fun importVariantLabel(variant: String) = UiText(requireNotNull(importProvider).variantLabel(variant))
    private val referenceRoot = File(app.filesDir, "studio_references").apply { mkdirs() }
    private var service: VoiceBoxBackendService? = null
    private var accelerationUpdates: Job? = null
    private var downloadUpdates: Job? = null
    private var resourceUpdates: Job? = null
    private var bound = false
    private var pendingRequest by mutableStateOf<GenerationContext?>(null)
    var warmupEnabled = true

    internal val modelPreview = BuildConfig.DEBUG && BuildConfig.MODEL_PREVIEW
    internal var tab by mutableStateOf(if (modelPreview) StudioTab.MODELS else StudioTab.CREATE)
    var models by mutableStateOf<List<CharacterModel>>(emptyList()); private set
    var voices by mutableStateOf<List<StudioVoice>>(emptyList()); private set
    var selectedId by mutableStateOf(store.selectedId); private set
    var text by mutableStateOf(store.text); private set
    internal val creationSettings = CreationSettings(
        store, modelsRepository, selectedModel = { selectedVoice?.model }, languagesFor = ::languages,
        canEdit = { !busy },
    ) { clearError ->
        if (clearError) generationError = null
        syncDefaults()
    }
    var loading by mutableStateOf(true); private set
    private var readyResources by mutableStateOf(emptySet<ModelRef>())
    fun resourcesReady(ref: ModelRef) = ref in readyResources
    val runtimeReady get() = selectedVoice?.model in readyResources
    var connected by mutableStateOf(false); private set
    var state by mutableStateOf(BackendServiceState()); private set
    var draft by mutableStateOf<VoiceDraft?>(null); private set
    internal var checkpointDraft by mutableStateOf<CheckpointDraft?>(null); private set
    var checkpointError by mutableStateOf<UiText?>(null); private set
    var checkpointRecognition by mutableStateOf<UiText?>(null); private set
    var officialDownload by mutableStateOf(OfficialDownloadState()); private set
    var choosingVoice by mutableStateOf(false); private set
    var fileBusy by mutableStateOf(false); private set
    var recordingMs by mutableStateOf<Long?>(null); private set
    var importing by mutableStateOf(false); private set
    var importMessage by mutableStateOf<UiText?>(null); private set
    var notice by mutableStateOf<UiText?>(null); private set
    var editorError by mutableStateOf<UiText?>(null); private set
    var generationError by mutableStateOf<UiText?>(null); private set
    var cancelling by mutableStateOf(false); private set
    var output by mutableStateOf(store.output?.takeIf { File(it.path).isFile }); private set
    var preview by mutableStateOf(PreviewState()); private set
    internal var streamPreview by mutableStateOf<StreamPlayback?>(null); private set
    private var probes by mutableStateOf(emptyMap<String, Set<ExecutionBackend>>())
    internal val qwenGpuRejected get() = bridgeStates?.get(QwenModelPackage.BACKEND) == BridgeInstallState.CURRENT &&
        probes[QwenModelPackage.BACKEND]?.contains(ExecutionBackend.WEBGPU) == false
    private val availableBackends get() = selectedVoice?.let(::modelFor)?.let { model ->
        model.executionModes intersect probes[model.ref.backendId].orEmpty()
    }.orEmpty()
    var gpuAvailable by mutableStateOf(false); private set
    var gpuEnabled by mutableStateOf(store.gpuEnabled); private set
    val deviceFacts = DeviceFacts.read(app)
    var deviceNpuAvailable by mutableStateOf<Boolean?>(null); private set
    private var auxiliaryLimits by mutableStateOf(emptyMap<ModelRef, Int>())
    private var auxiliarySelection by mutableStateOf(emptyMap<String, List<String>>())
    val auxiliaryLimit get() = auxiliaryLimits[selectedVoice?.model] ?: 0
    val auxiliaryChoices get() = voices.filter { it.audioPath.isNotBlank() && it.audioPath != selectedVoice?.audioPath }.distinctBy { it.audioPath }
    val auxiliaryPaths get() = auxiliarySelection[selectedId].orEmpty().filter { path -> auxiliaryChoices.any { it.audioPath == path } }.take(auxiliaryLimit)
    fun toggleAuxiliary(path: String) {
        if (busy || auxiliaryChoices.none { it.audioPath == path }) return
        val paths = auxiliaryPaths
        if (path !in paths && paths.size >= auxiliaryLimit) return
        auxiliarySelection = auxiliarySelection + (selectedId to if (path in paths) paths - path else paths + path)
        syncDefaults()
    }

    var deviceAccelerationEnabled by mutableStateOf(settings.deviceAccelerationEnabled); private set
    var theme by mutableStateOf(settings.themeMode); private set
    var apiEnabled by mutableStateOf(settings.apiEnabled); private set
    var apiPort by mutableStateOf(settings.apiPort.toString()); private set
    var apiPortError by mutableStateOf<UiText?>(null); private set

    val audio = AudioPreview(app, { preview = it }, { notify(UiText(R.string.audio_play_failed)) })
    private val streamAudio = StreamingPlayback(app, viewModelScope, { streamPreview = it }) { error ->
        Log.e("VoiceStudio", "Stream playback failed", error)
        cancel()
        notify(UiText(R.string.audio_play_failed))
    }
    internal fun toggleStreamPause() = streamAudio.togglePause()
    internal fun stopStream() {
        cancel()
        streamAudio.stop()
    }
    private val recorder = ReferenceRecorder(app, referenceRoot, { recordingMs = it }, ::finishReferenceRecording) {
        cancelReferenceRecording()
        referenceRecordingError(UiText(R.string.recording_interrupted))
    }
    val selectedVoice get() = voices.firstOrNull { it.id == selectedId }
    val busy get() = state.busy || pendingRequest != null || importing ||
        (officialDownload.requestId != null && !officialDownload.finished) || streamPreview != null
    val backend get() = if (gpuEnabled && ExecutionBackend.WEBGPU in availableBackends) ExecutionBackend.WEBGPU else ExecutionBackend.CPU
    val canGenerate get() = connected && !loading && !busy && !fileBusy && text.isNotBlank() && runtimeReady && availableBackends.isNotEmpty()

    private val listener = BackendServiceListener { next ->
        val ownRequest = pendingRequest != null && next.requestId == pendingRequest?.requestId
        state = next
        val idleOrError = next.stage == GenerationStage.IDLE || next.stage == GenerationStage.ERROR
        val path = next.latestOutputFilePath?.takeUnless { idleOrError && !File(it).isFile }
        if (path != null && !(ownRequest && next.stage == GenerationStage.COMPLETED)) {
            saveLatestOutput(path, app.getString(R.string.generate_speech), "", next.latestOutputDurationMs)
        } else if (idleOrError && output?.let { it.path == next.latestOutputFilePath || !File(it.path).isFile } == true) {
            audio.stop()
            output = null
            store.output = null
        }
    }

    private fun saveLatestOutput(path: String, name: String, sourceText: String, durationMs: Long) {
        if (path == output?.path) return
        output = StudioOutput(path, name, sourceText, durationMs)
        store.output = output
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VoiceBoxBackendService.LocalBinder).getService()
            loading = true
            connected = true
            val backendService = service!!
            backendService.addListener(listener)
            accelerationUpdates?.cancel()
            accelerationUpdates = viewModelScope.launch {
                backendService.backendCapabilities.collect {
                    probes = it
                    syncDefaults()
                }
            }
            downloadUpdates?.cancel()
            downloadUpdates = viewModelScope.launch {
                backendService.catalogReady.await()
                var first = true
                backendService.officialDownload.collect {
                    officialDownload = it
                    if (first || it.finished) refreshResources()
                    first = false
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            streamAudio.stop()
            accelerationUpdates?.cancel()
            downloadUpdates?.cancel()
            resourceUpdates?.cancel()
            loading = false
            importing = false
            importMessage = null
            officialDownload = OfficialDownloadState()
            service = null
            connected = false
            probes = emptyMap()
            pendingRequest = null
            cancelling = false
            state = BackendServiceState()
            generationError = UiText(R.string.service_disconnected)
        }
    }

    init {
        viewModelScope.launch {
            gpuAvailable = withContext(Dispatchers.IO) { DeviceFacts.hasVulkanCompute }
            deviceNpuAvailable = withContext(Dispatchers.IO) { DeviceFacts.hasDeviceAccelerator }
        }
        runCatching {
            val intent = VoiceBoxBackendService.serviceIntent(app)
            ContextCompat.startForegroundService(app, intent)
            val binding = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bound = true
            check(binding)
        }.onFailure { loading = false; notify(UiText(R.string.service_start_failed)) }
    }

    private data class ResourcesSnapshot(val models: List<CharacterModel>, val voices: List<StudioVoice>,
        val ready: Set<ModelRef>, val catalog: List<OfficialVoice>, val auxiliary: Map<ModelRef, Int>,
        val bridgeStates: Map<String, BridgeInstallState>,
        val capabilities: Map<String, BackendCapabilities>, val packs: Map<String, List<ResourcePack>>,
        val presets: Map<ModelRef, List<SynthesisVoicePreset>>, val referenceInputs: Map<ModelRef, ReferenceInput>,
        val mingVoiceWeights: ResourcePack?, val qwenVoiceWeights: ResourcePack?)

    internal fun refreshResources() {
        resourceUpdates?.cancel()
        resourceUpdates = viewModelScope.launch {
            loading = true
            try {
                val data = withContext(Dispatchers.IO) {
                    val states = backendPackages.associate { it.spec.id to it.installState }
                    val providers = modelsRepository.providers.filter { states[it.id] == BridgeInstallState.CURRENT }
                    val qwenWeights = app.qwenModelPackage.pack(QwenModelPackage.TALKER) { ensureActive() }
                    val catalog = providers.flatMap { it.officialVoices() }
                    val ming = app.mingModelPackage
                    val mingWeights = if (states[MingModelPackage.BACKEND] == BridgeInstallState.CURRENT || modelPreview) ming.officialPack() else null
                    val codec = app.qwenModelPackage.pack(QwenModelPackage.CODEC) { ensureActive() }
                    val discovered = app.installedVoiceModels(providers, mingWeights?.installed == true, qwenWeights.installed)
                    val inputs = discovered.associate { it.ref to modelsRepository.provider(it).referenceInput(it) }
                    val packs = providers.associate { provider -> provider.id to provider.resourcePacks { ensureActive() } }.toMutableMap()
                    packs[QwenModelPackage.BACKEND] = listOf(codec)
                    val ready = discovered.map { it.ref }.filter { ref ->
                        packs.getValue(ref.backendId).all { it.installed || (it.variants.isNotEmpty() && ref.variant !in it.variants) }
                    }.toSet()
                    val defaults = discovered.mapNotNull { model ->
                        val reference = runCatching { modelsRepository.findReferenceExample(model) }.getOrNull()
                        if (reference != null) {
                            StudioVoice(defaultVoiceId(model.ref), model.displayName,
                                model.ref, reference.audioFile.absolutePath,
                                reference.audioFile.name, reference.referenceText.orEmpty(), builtIn = true, referenceLanguage = reference.language)
                        } else if (inputs.getValue(model.ref) != ReferenceInput.REQUIRED) {
                            StudioVoice(defaultVoiceId(model.ref), model.displayName, model.ref, "", "", "", builtIn = true)
                        } else null
                    }
                    val voices = (defaults + store.voices()).map { voice ->
                        if (voice.durationMs > 0 || voice.audioPath.isEmpty()) voice else voice.copy(
                            durationMs = runCatching { duration(voice.audioPath) }.getOrDefault(0))
                    }
                    val auxiliary = discovered.filter { modelsRepository.provider(it).supportsAuxiliaryReferences(it) }.associate { it.ref to it.maxAuxiliaryReferences }
                    val presets = discovered.associate { it.ref to modelsRepository.provider(it).voicePresets(it) }
                    val mingPacks = ming.modelPacks(modelPreview || modelsRepository.provider(MingModelPackage.BACKEND).npuAvailable)
                    ResourcesSnapshot(discovered, voices, ready, catalog, auxiliary, states,
                        providers.associate { it.id to it.capabilities }, packs + (MingModelPackage.BACKEND to mingPacks),
                        presets, inputs, mingWeights, qwenWeights)
                }
                models = data.models
                referenceInputs = data.referenceInputs
                creationSettings.updatePresets(data.presets)
                voices = data.voices.map { it.copy(avatarPath = avatars.path(it.id)) }
                readyResources = data.ready
                officialModels = data.catalog
                auxiliaryLimits = data.auxiliary
                bridgeStates = data.bridgeStates
                resourcePacks = data.packs
                mingVoiceWeights = data.mingVoiceWeights
                qwenVoiceWeights = data.qwenVoiceWeights
                capabilities = data.capabilities
                restoreVoiceSelection()
                syncDefaults()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e("VoiceStudio", "Cannot load voices", error)
                notify(UiText(R.string.voices_load_failed))
            } finally {
                if (isActive) loading = false
            }
        }
    }

    fun editCheckpointLanguage(language: String) {
        if (!importing) checkpointDraft = checkpointDraft?.copy(referenceLanguage = language)
    }

    fun updateText(value: String) { text = value; store.text = value; generationError = null }
    fun select(voice: StudioVoice) {
        if (busy) return
        selectedId = voice.id
        store.selectedId = voice.id
        creationSettings.selectReference(voice.model)
        generationError = null
        audio.stop()
        syncDefaults()
    }
    private fun defaultVoiceId(ref: ModelRef) = "default:${ref.backendId}/${ref.variant}/${ref.modelId}"

    fun modelFor(voice: StudioVoice): CharacterModel? = models.firstOrNull { it.ref == voice.model }
    private fun restoreVoiceSelection() {
        if (selectedVoice?.let(::modelFor) != null) return
        val fallback = (voices.firstOrNull { resourcesReady(it.model) } ?: voices.firstOrNull { modelFor(it) != null })?.id.orEmpty()
        if (selectedId == fallback) return
        selectedId = fallback
        store.selectedId = fallback
        generationError = null
    }

    fun modelFor(official: OfficialVoice): CharacterModel? = models.firstOrNull { it.ref == official.ref }
    fun isOfficial(ref: ModelRef) = officialModels.any { it.ref == ref }
    fun voiceFor(official: OfficialVoice): StudioVoice? = voices.firstOrNull { it.builtIn && it.model == official.ref }
    fun voiceName(voice: StudioVoice, resources: Resources): String =
        officialModels.firstOrNull { voice.builtIn && it.ref == voice.model }
            ?.displayName(resources) ?: voice.name
    private fun nameExists(name: String, voiceId: String? = null, modelRef: ModelRef? = null): Boolean {
        val names = officialModels.filterNot { it.ref == modelRef }.map { it.name } +
            models.filterNot { it.ref == modelRef }.map { it.displayName } +
            voices.filterNot { it.id == voiceId || (it.builtIn && it.model == modelRef) }.map { it.name }
        return names.any { it.trim().equals(name.trim(), ignoreCase = true) }
    }
    fun downloadOfficial(official: OfficialVoice) {
        if (busy || fileBusy) return
        if (service?.downloadOfficialModel(official) != true) notify(UiText(R.string.engine_busy))
    }
    fun installModelParts(id: String, part: String?) {
        if (busy || fileBusy) return
        if (service?.installModelParts(id, part) != true) notify(UiText(R.string.engine_busy))
    }
    internal fun downloadMingVoices() {
        if (busy || fileBusy) return
        if (service?.downloadMingVoices() != true) notify(UiText(R.string.engine_busy))
    }
    fun removeModelPart(id: String, part: String) {
        if (loading || busy || fileBusy) return
        if (service?.removeModelPart(id, part, ::onModelRemoval) != true) notify(UiText(R.string.engine_busy))
    }
    internal fun removeOfficialWeights(voice: OfficialVoice) {
        if (loading || busy || fileBusy) return
        if (service?.removeOfficialWeights(voice, ::onModelRemoval) != true) notify(UiText(R.string.engine_busy))
    }
    private fun onModelRemoval(result: Result<Unit>) {
        result.onFailure {
            Log.e("VoiceStudio", "Model component removal failed", it)
            notify(it.userText() ?: UiText(R.string.model_remove_failed))
        }
        refreshResources()
    }
    fun cancelOfficialDownload() {
        val requestId = officialDownload.requestId ?: return
        if (service?.cancelOfficialDownload(requestId) == true) notify(UiText(R.string.download_cancelled))
    }
    fun openVoicePicker() { if (!busy && !fileBusy) choosingVoice = true }
    fun closeVoicePicker() { choosingVoice = false }
    fun chooseVoice(voice: StudioVoice) {
        if (busy || modelFor(voice) == null) return
        select(voice)
        choosingVoice = false
        tab = StudioTab.CREATE
    }
    fun beginVoice(voice: StudioVoice? = null) {
        if (busy || fileBusy) return
        editorError = null
        draft = if (voice == null) VoiceDraft(modelRef = selectedVoice?.model?.takeIf(::acceptsReference) ?: cloneModels.firstOrNull()?.ref)
        else VoiceDraft(voice.id, voice.name, voice.model,
            voice.audioPath, voice.audioName, voice.transcript, voice.durationMs, voice.referenceLanguage, voice.avatarPath)
    }
    fun edit(value: VoiceDraft) { draft = value; editorError = null }
    fun closeEditor() {
        if (fileBusy) return
        audio.stop()
        draft?.audioPath?.let(::deleteDraftFile)
        draft?.avatarPath?.let(avatars::discard)
        draft = null
        editorError = null
    }

    fun pickAvatar(uri: Uri, cameraFile: File? = null) {
        if (busy || fileBusy || (draft == null && checkpointDraft == null)) { cameraFile?.delete(); return }
        val forCheckpoint = checkpointDraft != null
        fileBusy = true
        referenceRecordingError(null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { runCatching { avatars.import(uri) } } finally { cameraFile?.delete() }
            }
            result.onSuccess { path ->
                if (forCheckpoint) checkpointDraft?.let {
                    avatars.discard(it.avatarPath)
                    checkpointDraft = it.copy(avatarPath = path)
                } else draft?.let {
                    avatars.discard(it.avatarPath)
                    draft = it.copy(avatarPath = path)
                }
            }.onFailure { referenceRecordingError(UiText(R.string.image_read_failed)) }
            fileBusy = false
        }
    }

    fun pickReference(uri: Uri) {
        if (busy || fileBusy || (draft == null && checkpointDraft == null)) return
        val forCheckpoint = checkpointDraft != null
        fileBusy = true
        editorError = null
        checkpointError = null
        audio.stop()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val picked = ReferenceAudioPicker(app).copyToAppCache(uri)
                    try {
                        val milliseconds = duration(picked.cachedFile.absolutePath)
                        requireUi(milliseconds in 500..30_000) { UiText(R.string.reference_duration_limit) }
                        val target = File(referenceRoot, "draft-${UUID.randomUUID()}.${picked.cachedFile.extension}")
                        Files.move(picked.cachedFile.toPath(), target.toPath())
                        Triple(target.absolutePath, picked.displayName, milliseconds)
                    } finally { picked.cachedFile.delete() }
                }
            }
            result.onSuccess { (path, name, milliseconds) ->
                applyReference(forCheckpoint, path, name, milliseconds)
            }.onFailure {
                Log.w("VoiceStudio", "Reference import failed", it)
                val message = it.userText() ?: UiText(R.string.reference_read_failed)
                if (forCheckpoint) checkpointError = message else editorError = message
            }
            fileBusy = false
        }
    }

    private fun applyReference(forCheckpoint: Boolean, path: String, name: String, milliseconds: Long) {
        if (forCheckpoint) checkpointDraft?.let {
            deleteDraftFile(it.audioPath)
            checkpointDraft = it.copy(audioPath = path, audioName = name, durationMs = milliseconds)
        } else draft?.let {
            deleteDraftFile(it.audioPath)
            draft = it.copy(audioPath = path, audioName = name, durationMs = milliseconds)
        }
    }

    fun removeReference() {
        if (busy || fileBusy) return
        audio.stop()
        applyReference(checkpointDraft != null, "", "", 0)
        referenceRecordingError(null)
    }

    fun referenceRecordingError(message: UiText?) {
        if (checkpointDraft != null) checkpointError = message else editorError = message
    }

    fun startReferenceRecording() {
        if (busy || fileBusy || (draft == null && checkpointDraft == null)) return
        audio.stop()
        editorError = null
        checkpointError = null
        runCatching { recorder.start() }.onSuccess {
            fileBusy = true
            recordingMs = 0
        }.onFailure { referenceRecordingError(UiText(R.string.recording_start_failed)) }
    }

    fun finishReferenceRecording() {
        if (recordingMs == null) return
        val forCheckpoint = checkpointDraft != null
        recordingMs = null
        val recorded = runCatching { recorder.finish() }.getOrElse {
            fileBusy = false
            referenceRecordingError(UiText(R.string.recording_incomplete))
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                duration(recorded.absolutePath).also { require(it in 500..30_500) }
            } }
            result.onSuccess { applyReference(forCheckpoint, recorded.absolutePath, app.getString(R.string.recording_filename), it) }
                .onFailure { recorded.delete(); referenceRecordingError(UiText(R.string.recording_minimum)) }
            fileBusy = false
        }
    }

    fun cancelReferenceRecording() {
        if (recordingMs == null) return
        recorder.cancel()
        recordingMs = null
        fileBusy = false
    }

    fun saveVoice() {
        val value = draft ?: return
        if (fileBusy || busy) return
        if (value.name.isBlank() || value.modelRef == null || cloneModels.none { it.ref == value.modelRef }) {
            editorError = UiText(R.string.voice_fields_required)
            return
        }
        if (!optionalReference(value.modelRef) && (value.audioPath.isBlank() || value.transcript.isBlank())) {
            editorError = UiText(R.string.reference_required)
            return
        }
        if (value.audioPath.isBlank() && value.transcript.isNotBlank()) {
            editorError = UiText(R.string.transcript_needs_audio)
            return
        }
        if (nameExists(value.name, voiceId = value.id)) {
            editorError = UiText(R.string.voice_name_exists)
            return
        }
        fileBusy = true
        audio.stop()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val previous = store.voices()
                    val old = previous.firstOrNull { it.id == value.id }?.audioPath
                    val source = value.audioPath.takeIf { it.isNotBlank() }?.let(::File)
                    val copy = source?.takeIf { it.path != old }?.let { File(referenceRoot, "${UUID.randomUUID()}.${it.extension}") }
                    val voice = StudioVoice(value.id, value.name.trim(), value.modelRef,
                        copy?.absolutePath ?: value.audioPath, value.audioName, value.transcript.trim(), value.durationMs,
                        referenceLanguage = value.referenceLanguage, avatarPath = value.avatarPath)
                    try {
                        source?.let {
                            require(it.isFile)
                            if (copy != null) it.copyTo(copy)
                        }
                        store.save(previous.filterNot { it.id == voice.id } + voice)
                    } catch (error: Exception) {
                        copy?.delete()
                        throw error
                    }
                    avatars.save(voice.id, value.avatarPath)
                    old?.let {
                        File(it).takeIf { file -> file.path != voice.audioPath && file.parentFile == referenceRoot }?.delete()
                    }
                    deleteDraftFile(value.audioPath)
                    voice
                }
            }
            fileBusy = false
            result.onSuccess { voice ->
                voices = voices.filterNot { it.id == voice.id } + voice
                draft = null
                select(voice)
                tab = StudioTab.CREATE
                notify(UiText(R.string.voice_saved))
                if (loading) refreshResources()
            }.onFailure { editorError = UiText(R.string.voice_save_failed) }
        }
    }

    fun deleteVoice(voice: StudioVoice) {
        if (busy || fileBusy || voice.builtIn) return
        fileBusy = true
        audio.stop()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.save(store.voices().filterNot { it.id == voice.id })
                    avatars.save(voice.id, "")
                    File(voice.audioPath).takeIf { it.parentFile == referenceRoot }?.delete()
                }
            }
            fileBusy = false
            result.onSuccess {
                voices = voices.filterNot { it.id == voice.id }
                restoreVoiceSelection()
                syncDefaults()
                if (loading) refreshResources()
            }.onFailure { notify(UiText(R.string.voice_delete_failed)) }
        }
    }

    fun generate() {
        if (!canGenerate) return
        val voice = selectedVoice ?: return
        val model = modelFor(voice) ?: return
        val provider = modelsRepository.provider(model)
        val options = try {
            creationSettings.requestOptions()
        } catch (error: IllegalArgumentException) {
            Log.w("VoiceStudio", "Synthesis options are unavailable", error)
            generationError = UiText(R.string.generation_failed)
            refreshResources()
            return
        }
        val usesReference = acceptsReference(model.ref) && provider.usesReference(options) &&
            (!optionalReference(model.ref) || voice.audioPath.isNotBlank())
        val language = creationSettings.outputLanguage.takeIf { provider.usesOutputLanguage(options) }.orEmpty()
        val synthesisText = text.trim()
        val promptLanguage = if (usesReference) referenceLanguage(voice) else ""
        val languageError = provider.languageError(language, synthesisText)
            ?: if (promptLanguage.isNotEmpty()) provider.languageError(promptLanguage, voice.transcript) else null
        if (languageError != null) { generationError = UiText(languageError); return }
        if (usesReference && !File(voice.audioPath).isFile) {
            generationError = UiText(R.string.reference_missing)
            return
        }
        audio.stop()
        generationError = null
        val request = GenerationRequest(model, language,
            promptLanguage = promptLanguage, synthesisText = synthesisText,
            referenceAudioPath = voice.audioPath.takeIf { usesReference }.orEmpty(),
            referenceText = voice.transcript.takeIf { usesReference }.orEmpty(),
            auxiliaryReferenceAudioPaths = auxiliaryPaths,
            options = options)
        if (creationSettings.streaming) {
            val directory = File(app.cacheDir, "synthesis-streams/${request.context.requestId}")
            if (runCatching { streamAudio.start(directory, provider.streamSampleRate) }.isFailure) {
                generationError = UiText(R.string.generation_failed)
                return
            }
            request.context.streamDirectory = directory.absolutePath
        }
        pendingRequest = request.context
        val name = creationSettings.preset?.id ?: voiceName(voice, app.resources)
        val accepted = service?.synthesize(backend, request) { result ->
            if (pendingRequest !== request.context) return@synthesize
            pendingRequest = null
            cancelling = false
            if (result is InferResult.Success) {
                if (request.context.streamDirectory != null) streamAudio.finishSynthesis()
                saveLatestOutput(result.outputFile.absolutePath, name, request.synthesisText, result.audioDurationMs)
            } else {
                if (request.context.streamDirectory != null) streamAudio.stop()
                if (result is InferResult.Error && request.context.cancellationReason == null) {
                    Log.e("VoiceStudio", result.message)
                    when (result.code) {
                        "MING_CLONE_RESOURCES_REQUIRED" -> {
                            notify(UiText(R.string.ming_clone_resources_required))
                            tab = StudioTab.MODELS
                        }
                        "EMPTY_SEMANTIC" -> generationError = UiText(R.string.generation_empty)
                        else -> generationError = UiText(R.string.generation_failed)
                    }
                }
            }
        } == true
        if (!accepted) {
            streamAudio.stop()
            pendingRequest = null
            generationError = UiText(R.string.engine_other_task)
        }
    }

    fun cancel() {
        val id = pendingRequest?.requestId ?: return
        if (service?.cancelGeneration(id) == true) cancelling = true
    }

    private fun referenceLanguage(voice: StudioVoice) = if (voice.model.backendId == QwenModelPackage.BACKEND) ""
        else ReferenceLanguage.resolve(voice.referenceLanguage, voice.transcript)

    private fun syncDefaults() {
        if (busy) return
        val backendService = service ?: return
        val voice = selectedVoice
        val model = voice?.let(::modelFor)
        if (!runtimeReady || voice == null || model == null || availableBackends.isEmpty()) {
            backendService.setActiveDefaults(null)
            return
        }
        val options = runCatching { creationSettings.defaultOptions() }.getOrElse {
            backendService.setActiveDefaults(null)
            return
        }
        backendService.setActiveDefaults(ActiveDefaults(backend, model.ref, creationSettings.outputLanguage.takeIf { creationSettings.usesOutputLanguage }.orEmpty(), voice.audioPath,
            voice.transcript, promptLanguage = referenceLanguage(voice),
            options = options, auxiliaryReferenceAudioPaths = auxiliaryPaths), warmupEnabled)
    }

    fun importRuntimePackage(uri: Uri) {
        val backendId = archiveProvider ?: return
        if (busy || fileBusy) return
        importing = true
        importMessage = UiText(R.string.import_resources_progress)
        audio.stop()
        val accepted = service?.importRuntimePackage(backendId, uri) { result ->
            importing = false
            importMessage = null
            notify(result.fold({ UiText(R.string.import_resources_complete) }, {
                Log.e("VoiceStudio", "Resource import failed", it)
                it.userText() ?: UiText(R.string.import_resources_failed)
            }))
            refreshResources()
        } == true
        if (!accepted) {
            importing = false
            importMessage = null
            notify(UiText(R.string.import_engine_busy))
        }
    }

    internal fun beginCheckpointImport(format: CheckpointFormat) {
        if (busy || fileBusy || format !in checkpointFormats) return
        checkpointDraft = CheckpointDraft(format)
        checkpointError = null
        checkpointRecognition = null
        importMessage = null
    }

    fun beginEditVoice(voice: StudioVoice) {
        if (busy || fileBusy) return
        if (voice.builtIn && VoiceImport.CHECKPOINT_PAIR !in capabilities[voice.model.backendId]?.imports.orEmpty()) {
            beginVoice(voice.copy(id = UUID.randomUUID().toString(), name = ""))
            return
        }
        if (!voice.builtIn) { beginVoice(voice); return }
        val model = modelFor(voice) ?: return
        checkpointDraft = CheckpointDraft(CheckpointFormat.GPT, name = voice.name, audioPath = voice.audioPath,
            firstName = model.sourceFilenames["gpt"].orEmpty(), secondName = model.sourceFilenames["sovits"].orEmpty(),
            audioName = voice.audioName, transcript = voice.transcript, durationMs = voice.durationMs,
            referenceLanguage = voice.referenceLanguage, editingRef = model.ref, avatarPath = voice.avatarPath)
        checkpointError = null
        checkpointRecognition = importVariantLabel(model.ref.variant)
        importMessage = null
    }

    fun editOfficial(official: OfficialVoice) {
        if (busy || fileBusy) return
        val model = modelFor(official) ?: return
        val voice = voiceFor(official)
        if (official.ref.backendId == QwenModelPackage.BACKEND) {
            voice?.let(::beginEditVoice)
            return
        }
        checkpointDraft = CheckpointDraft(CheckpointFormat.GPT, name = official.name, firstName = model.sourceFilenames["gpt"].orEmpty(),
            secondName = model.sourceFilenames["sovits"].orEmpty(), editingRef = model.ref,
            audioPath = voice?.audioPath.orEmpty(), audioName = voice?.audioName.orEmpty(),
            transcript = voice?.transcript.orEmpty(), durationMs = voice?.durationMs ?: 0,
            referenceLanguage = voice?.referenceLanguage ?: "auto", avatarPath = voice?.avatarPath.orEmpty())
        checkpointError = null
        checkpointRecognition = importVariantLabel(official.ref.variant)
        importMessage = null
    }

    fun clearCheckpointReplacement(first: Boolean) {
        if (busy || fileBusy) return
        checkpointDraft = checkpointDraft?.let { value ->
            val model = models.firstOrNull { it.ref == value.editingRef }
            if (first) value.copy(first = null, firstName = model?.sourceFilenames?.get("gpt").orEmpty())
            else value.copy(second = null, secondName = model?.sourceFilenames?.get("sovits").orEmpty())
        }
        checkpointError = null
        checkpointRecognition = checkpointDraft?.takeIf { it.first == null && it.second == null }
            ?.editingRef?.let(::variantLabel)
    }

    fun closeCheckpointImport() {
        if (importing || fileBusy) return
        audio.stop()
        checkpointDraft?.audioPath?.let(::deleteDraftFile)
        checkpointDraft?.avatarPath?.let(avatars::discard)
        checkpointDraft = null
        checkpointError = null
    }

    fun editCheckpointName(name: String) {
        if (!importing) checkpointDraft = checkpointDraft?.copy(name = name.take(80))
    }

    fun editCheckpointTranscript(text: String) {
        if (!importing) checkpointDraft = checkpointDraft?.copy(transcript = text)
        checkpointError = null
    }

    fun pickCheckpoint(uri: Uri, first: Boolean) {
        val format = checkpointDraft?.format ?: return
        if (busy || fileBusy) return
        fileBusy = true
        viewModelScope.launch {
            val filename = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            }
            val extension = if (first) format.firstExtension else format.secondExtension
            if (!filename.endsWith(extension, ignoreCase = true)) {
                checkpointError = UiText(R.string.checkpoint_extension, extension)
                fileBusy = false
                return@launch
            }
            checkpointDraft?.let { value ->
                checkpointDraft = if (first) value.copy(first = uri, firstName = filename) else {
                    val genericName = format == CheckpointFormat.QWEN && filename.equals("model.safetensors", ignoreCase = true)
                    val name = if (genericName) value.name else value.name.ifBlank { filename.substringBeforeLast('.').take(80) }
                    value.copy(second = uri, secondName = filename, name = name)
                }
            }
            checkpointError = null
            val selected = checkpointDraft
            checkpointRecognition = null
            if (format == CheckpointFormat.GPT && selected?.first != null && selected.second != null) {
                checkpointRecognition = UiText(R.string.model_identifying)
                checkpointRecognition = withContext(Dispatchers.IO) {
                    runCatching { importProvider?.let { service?.previewCheckpoint(it.id, selected.second) } }
                        .fold({ it?.let { importVariantLabel(it) } ?: UiText(R.string.model_identify_on_import) }, { UiText(R.string.model_identify_failed) })
                }
            }
            fileBusy = false
        }
    }

    fun importCheckpoints() {
        val value = checkpointDraft ?: return
        if (busy || fileBusy) return
        val editing = value.editingRef != null
        if (value.name.isBlank() || (!editing && (value.first == null || value.second == null))) {
            checkpointError = UiText(R.string.checkpoint_fields_required, value.format.firstExtension, value.format.secondExtension)
            return
        }
        val editedModel = value.editingRef
        if (nameExists(value.name, modelRef = editedModel)) {
            checkpointError = UiText(R.string.voice_name_exists)
            return
        }
        if (editing && (value.first == null) != (value.second == null)) {
            checkpointError = UiText(R.string.checkpoint_replace_pair)
            return
        }
        val reference = if (value.format == CheckpointFormat.GPT) {
            if (value.audioPath.isBlank() || value.transcript.isBlank()) {
                checkpointError = UiText(R.string.checkpoint_reference_required)
                return
            }
            ReferenceExample(File(value.audioPath), value.transcript, value.referenceLanguage)
        } else null
        importing = true
        checkpointError = null
        importMessage = if (editing && value.first == null) UiText(R.string.voice_saving) else UiText(R.string.model_reading)
        audio.stop()
        val onComplete: (Result<ImportedVoice>) -> Unit = { result ->
            importing = false
            result.onSuccess { imported ->
                deleteDraftFile(value.audioPath)
                checkpointDraft = null
                importMessage = null
                selectedId = defaultVoiceId(imported.ref)
                store.selectedId = selectedId
                avatars.save(selectedId, value.avatarPath)
                refreshResources()
                val needsCodec = value.format == CheckpointFormat.QWEN &&
                    resourcePacks[QwenModelPackage.BACKEND].orEmpty().none { it.id == QwenModelPackage.CODEC && it.installed }
                val (destination, message) = when {
                    needsCodec -> StudioTab.MODELS to R.string.qwen_import_needs_codec
                    editing -> StudioTab.VOICES to R.string.voice_updated
                    else -> StudioTab.CREATE to R.string.voice_imported
                }
                tab = destination
                notify(UiText(message))
            }.onFailure {
                Log.e("VoiceStudio", "Checkpoint import failed", it)
                checkpointError = it.userText()
                    ?: UiText(R.string.checkpoint_import_failed)
                importMessage = null
                refreshResources()
            }
        }
        val accepted = if (editing && value.first == null) {
            service?.editCharacterReference(requireNotNull(editedModel), value.name, requireNotNull(reference), onComplete) == true
        } else {
            val source = when (value.format) {
                CheckpointFormat.GPT -> VoiceCheckpointSource.GptSoVits(value.first!!, value.second!!, reference, editedModel,
                    value.firstName, value.secondName)
                CheckpointFormat.QWEN -> VoiceCheckpointSource.Qwen3(value.first!!, value.second!!)
            }
            service?.importVoiceCheckpoint(value.name, source, onProgress = { importMessage = it }, onComplete = onComplete) == true
        }
        if (!accepted) {
            importing = false
            checkpointError = UiText(R.string.import_engine_busy)
            importMessage = null
        }
    }

    fun setGpu(enabled: Boolean) {
        if (busy) return
        gpuEnabled = enabled
        store.gpuEnabled = enabled
        syncDefaults()
    }
    fun setDeviceAcceleration(enabled: Boolean) {
        if (busy) return
        deviceAccelerationEnabled = enabled
        settings.deviceAccelerationEnabled = enabled
        ContextCompat.startForegroundService(app, VoiceBoxBackendService.serviceIntent(app)
            .setAction(VoiceBoxBackendService.ACTION_RELOAD_INFERENCE_SETTINGS))
    }
    fun changeTheme(value: AppSettingsRepository.ThemeMode) { theme = value; settings.themeMode = value }
    fun setApi(enabled: Boolean) {
        apiEnabled = enabled
        settings.apiEnabled = enabled
        ContextCompat.startForegroundService(app, VoiceBoxBackendService.serviceIntent(app))
    }
    fun editPort(value: String) { apiPort = value; apiPortError = null }
    fun applyPort() {
        val port = apiPort.toIntOrNull()
        if (port == null || port !in 1024..65535) { apiPortError = UiText(R.string.port_range); return }
        settings.apiPort = port
        ContextCompat.startForegroundService(app, VoiceBoxBackendService.serviceIntent(app))
        notify(UiText(R.string.port_updated))
    }
    fun export(uri: Uri, path: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                File(path).inputStream().use { source ->
                    app.contentResolver.openOutputStream(uri, "wt")?.use { source.copyTo(it) }
                        ?: errorUi(UiText(R.string.file_write_failed))
                }
            }
        }
        notify(if (result.isSuccess) UiText(R.string.audio_saved) else UiText(R.string.audio_save_failed))
    }
    fun notify(message: UiText) { notice = message }
    fun clearNotice() { notice = null }
    fun clearCheckpointError() { checkpointError = null }
    fun clearEditorError() { editorError = null }
    fun clearGenerationError() { generationError = null }
    fun clearPortError() { apiPortError = null }

    private fun deleteDraftFile(path: String) {
        File(path).takeIf { it.parentFile == referenceRoot && it.name.startsWith("draft-") }?.delete()
    }
    private fun duration(path: String): Long = MediaMetadataRetriever().use {
        it.setDataSource(path)
        require(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: errorUi(UiText(R.string.audio_duration_failed))
    }
    override fun onCleared() {
        if (pendingRequest?.streamDirectory != null) cancel()
        pendingRequest = null
        streamAudio.close()
        draft?.avatarPath?.let(avatars::discard)
        checkpointDraft?.avatarPath?.let(avatars::discard)
        recorder.close()
        service?.removeListener(listener)
        if (bound) app.unbindService(connection)
        audio.close()
    }
}

internal fun OfficialVoice.displayName(resources: android.content.res.Resources): String =
    if (variantLabelKey.isEmpty()) name else resources.getString(R.string.official_model_name, UiText(variantLabelKey).resolve(resources))
