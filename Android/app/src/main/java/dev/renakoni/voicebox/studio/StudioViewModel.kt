package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.bridge.AccelerationInfo
import dev.renakoni.voicebox.bridge.OfficialVoice
import dev.renakoni.voicebox.bridge.VoiceImport
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.ImportedVoice
import dev.renakoni.voicebox.core.OfficialDownloadState
import dev.renakoni.voicebox.VoiceBoxApplication

import dev.renakoni.voicebox.R
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
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.BackendCapabilities
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationContext
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.ReferenceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

data class VoiceDraft(val id: String = UUID.randomUUID().toString(), val name: String = "",
    val modelRef: ModelRef? = null, val audioPath: String = "", val audioName: String = "",
    val transcript: String = "", val durationMs: Long = 0, val referenceLanguage: String = "auto", val avatarPath: String = "")

data class CheckpointDraft(val name: String = "", val gpt: Uri? = null, val sovits: Uri? = null,
    val gptName: String = "", val sovitsName: String = "", val audioPath: String = "",
    val audioName: String = "", val transcript: String = "", val durationMs: Long = 0, val referenceLanguage: String = "auto",
    val editingRef: ModelRef? = null, val avatarPath: String = "")

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceBoxApplication
    private val store = StudioStore(app)
    private val avatars = VoiceAvatarStore(app)
    private val modelsRepository = app.backends
    private val settings = AppSettingsRepository(app)
    val backendPackages = app.bridgePackages.packages.sortedWith(compareBy({ !it.spec.bundled }, { it.spec.displayName }))
    var installedPackages by mutableStateOf<Set<String>?>(null); private set
    private var capabilities by mutableStateOf(emptyMap<String, BackendCapabilities>())
    private val selectedCapabilities get() = capabilities[selectedVoice?.model?.backendId]
    var officialModels by mutableStateOf(emptyList<OfficialVoice>()); private set
    private fun providerFor(type: VoiceImport) = capabilities.entries.firstOrNull { type in it.value.imports }?.key
    private val importProvider get() = (checkpointDraft?.editingRef?.backendId ?: providerFor(VoiceImport.CHECKPOINT_PAIR))
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

    val canImportCheckpoints get() = importProvider != null
    val importLanguages get() = importProvider?.capabilities?.languages.orEmpty()
    fun languages(ref: ModelRef?) = capabilities[ref?.backendId]?.languages.orEmpty()
    private fun importVariantLabel(variant: String) = UiText(requireNotNull(importProvider).variantLabel(variant))
    val optionControls get() = selectedVoice?.let { modelsRepository.provider(it.model.backendId).controls }.orEmpty()

    private val referenceRoot = File(app.filesDir, "studio_references").apply { mkdirs() }
    private var service: VoiceBoxBackendService? = null
    private var accelerationUpdates: Job? = null
    private var downloadUpdates: Job? = null
    private var resourceUpdates: Job? = null
    private var bound = false
    private var pendingRequest: GenerationContext? = null
    private var pendingVoiceName = ""
    private var pendingText = ""
    private var handledOutput: String? = null
    var warmupEnabled = true

    var tab by mutableStateOf(0)
    var models by mutableStateOf<List<CharacterModel>>(emptyList()); private set
    var voices by mutableStateOf<List<StudioVoice>>(emptyList()); private set
    var selectedId by mutableStateOf(store.selectedId); private set
    var text by mutableStateOf(store.text); private set
    private var languagesByBackend by mutableStateOf(modelsRepository.providers.associate { it.id to store.outputLanguage(it.id) })
    val outputLanguage: String get() {
        val voice = selectedVoice ?: return ""
        val languages = languages(voice.model)
        return languagesByBackend[voice.model.backendId]?.takeIf { it in languages } ?: languages.firstOrNull().orEmpty()
    }
    private var optionsByBackend by mutableStateOf(modelsRepository.providers.associate { it.id to store.options(it.id) })
    val synthesisOptions get() = optionsByBackend[selectedVoice?.model?.backendId].orEmpty()
    var loading by mutableStateOf(true); private set
    private var readyResources by mutableStateOf(emptySet<ModelRef>())
    val runtimeReady get() = selectedVoice?.model in readyResources
    var connected by mutableStateOf(false); private set
    var state by mutableStateOf(BackendServiceState()); private set
    var draft by mutableStateOf<VoiceDraft?>(null); private set
    var checkpointDraft by mutableStateOf<CheckpointDraft?>(null); private set
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
    var submitting by mutableStateOf(false); private set
    var output by mutableStateOf(store.output?.takeIf { File(it.path).isFile }); private set
    var preview by mutableStateOf(PreviewState()); private set
    private var backendModes by mutableStateOf(emptyMap<String, Set<ExecutionBackend>>())
    private val availableBackends get() = selectedVoice?.let(::modelFor)?.let { model ->
        model.executionModes intersect backendModes[model.ref.backendId].orEmpty()
    }.orEmpty()
    val gpuAvailable get() = ExecutionBackend.WEBGPU in availableBackends
    var gpuEnabled by mutableStateOf(store.gpuEnabled); private set
    private var accelerationByBackend by mutableStateOf(emptyMap<String, AccelerationInfo>())
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

    private var modelsWithNpuGraphs by mutableStateOf(emptySet<ModelRef>())
    val acceleration: AccelerationInfo get() {
        val ref = selectedVoice?.model
        val report = accelerationByBackend[ref?.backendId] ?: return AccelerationInfo(detail = "npu_unavailable")
        return if (report.available && ref !in modelsWithNpuGraphs) report.copy(available = false, detail = "npu_unavailable") else report
    }
    var deviceAccelerationEnabled by mutableStateOf(settings.deviceAccelerationEnabled); private set
    var theme by mutableStateOf(settings.themeMode); private set
    var apiEnabled by mutableStateOf(settings.apiEnabled); private set
    var apiPort by mutableStateOf(settings.apiPort.toString()); private set
    var apiPortError by mutableStateOf<UiText?>(null); private set

    val audio = AudioPreview(app, { preview = it }, { notify(UiText(R.string.audio_play_failed)) })
    private val recorder = ReferenceRecorder(app, referenceRoot, { recordingMs = it }, ::finishReferenceRecording) {
        cancelReferenceRecording()
        referenceRecordingError(UiText(R.string.recording_interrupted))
    }
    val selectedVoice get() = voices.firstOrNull { it.id == selectedId }
    val busy get() = state.busy || submitting || importing || officialDownload.running
    val backend get() = if (gpuAvailable && gpuEnabled) ExecutionBackend.WEBGPU else ExecutionBackend.CPU
    val canGenerate get() = connected && !loading && !busy && !fileBusy && text.isNotBlank() && runtimeReady && availableBackends.isNotEmpty()

    private val listener = BackendServiceListener { next ->
        val ownRequest = pendingRequest != null && next.requestId == pendingRequest?.requestId
        state = next
        if (ownRequest) {
            submitting = false
            if (!next.busy) {
                pendingRequest = null
                cancelling = false
                if (next.stage == GenerationStage.ERROR) {
                    Log.e("VoiceStudio", next.message)
                    generationError = if (next.errorCode == "EMPTY_SEMANTIC")
                        UiText(R.string.generation_empty)
                    else UiText(R.string.generation_failed)
                }
            }
        }
        val path = next.latestOutputFilePath
        if (next.stage == GenerationStage.COMPLETED && path != null && path != handledOutput) {
            handledOutput = path
            val name = if (ownRequest) pendingVoiceName else app.getString(R.string.generate_speech)
            val sourceText = if (ownRequest) pendingText else ""
            viewModelScope.launch {
                val duration = withContext(Dispatchers.IO) { runCatching { duration(path) }.getOrDefault(0) }
                if (handledOutput != path) return@launch
                output = StudioOutput(path, name, sourceText, duration)
                store.output = output
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VoiceBoxBackendService.LocalBinder).getService()
            connected = true
            val backendService = service!!
            handledOutput = output?.path
            backendService.addListener(listener)
            accelerationUpdates?.cancel()
            accelerationUpdates = viewModelScope.launch {
                launch { backendService.acceleration.collect { accelerationByBackend = it } }
                backendService.backendCapabilities.collect {
                    backendModes = it
                    syncDefaults()
                }
            }
            downloadUpdates?.cancel()
            downloadUpdates = viewModelScope.launch {
                backendService.catalogReady.await()
                var first = true
                backendService.officialDownload.collect {
                    officialDownload = it
                    if (first || it.completed || it.error != null) refreshResources()
                    first = false
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            accelerationUpdates?.cancel()
            downloadUpdates?.cancel()
            officialDownload = OfficialDownloadState()
            service = null
            connected = false
            backendModes = emptyMap()
            pendingRequest = null
            cancelling = false
            submitting = false
            state = BackendServiceState()
            generationError = UiText(R.string.service_disconnected)
        }
    }

    init {
        runCatching {
            val intent = VoiceBoxBackendService.serviceIntent(app)
            ContextCompat.startForegroundService(app, intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { loading = false; notify(UiText(R.string.service_start_failed)) }
    }

    private data class ResourcesSnapshot(val models: List<CharacterModel>, val voices: List<StudioVoice>,
        val ready: Set<ModelRef>, val catalog: List<OfficialVoice>, val npuGraphs: Set<ModelRef>, val auxiliary: Map<ModelRef, Int>, val installed: Set<String>, val capabilities: Map<String, BackendCapabilities>)

    private fun refreshResources() {
        resourceUpdates?.cancel()
        resourceUpdates = viewModelScope.launch {
            loading = true
            try {
                val data = withContext(Dispatchers.IO) {
                    val installed = backendPackages.filter { it.installed }.map { it.spec.id }.toSet()
                    val providers = modelsRepository.providers.filter { it.id in installed }
                    val catalog = providers.flatMap { it.officialVoices() }
                    val discovered = providers.flatMap { provider -> provider.discoverCharacterModels().filter { provider.inspect(it).isComplete } }
                    val ready = discovered.groupBy { it.ref.backendId to it.ref.variant }
                        .filterKeys { (id, variant) -> modelsRepository.provider(id).resourcesReady(variant) }
                        .values.flatten().map { it.ref }.toSet()
                    val available = discovered.filter { it.ref in ready || catalog.none { voice -> voice.ref == it.ref } }
                    val defaults = available.mapNotNull { model ->
                        runCatching { modelsRepository.findReferenceExample(model) }.getOrNull()?.let { reference ->
                            StudioVoice(defaultVoiceId(model.ref), model.displayName,
                                model.ref, reference.audioFile.absolutePath,
                                reference.audioFile.name, reference.referenceText.orEmpty(), builtIn = true, referenceLanguage = reference.language)
                        }
                    }
                    val voices = (defaults + store.voices()).map { voice ->
                        if (voice.durationMs > 0) voice else voice.copy(
                            durationMs = runCatching { duration(voice.audioPath) }.getOrDefault(0))
                    }
                    val npu = available.filter { modelsRepository.provider(it).hasNpuGraphs(it) }.map { it.ref }.toSet()
                    val auxiliary = available.filter { modelsRepository.provider(it).supportsAuxiliaryReferences(it) }.associate { it.ref to it.maxAuxiliaryReferences }
                    ResourcesSnapshot(available, voices, ready, catalog, npu, auxiliary, installed, providers.associate { it.id to it.capabilities })
                }
                models = data.models
                voices = data.voices.map { it.copy(avatarPath = avatars.path(it.id)) }
                readyResources = data.ready
                officialModels = data.catalog
                modelsWithNpuGraphs = data.npuGraphs
                auxiliaryLimits = data.auxiliary
                installedPackages = data.installed
                capabilities = data.capabilities
                if (voices.none { it.id == selectedId }) {
                    selectedId = voices.firstOrNull()?.id.orEmpty()
                    store.selectedId = selectedId
                }
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

    fun changeOutputLanguage(language: String) {
        if (busy) return
        val ref = selectedVoice?.model ?: return
        if (language !in languages(ref)) return
        languagesByBackend = languagesByBackend + (ref.backendId to language)
        store.saveLanguage(ref.backendId, language)
        generationError = null
        syncDefaults()
    }

    fun editCheckpointLanguage(language: String) {
        if (!importing) checkpointDraft = checkpointDraft?.copy(referenceLanguage = language)
    }

    fun updateText(value: String) { text = value; store.text = value; generationError = null }
    fun select(voice: StudioVoice) {
        if (busy) return
        selectedId = voice.id
        store.selectedId = voice.id
        generationError = null
        audio.stop()
        syncDefaults()
    }
    private fun defaultVoiceId(ref: ModelRef) = "default:${ref.backendId}/${ref.variant}/${ref.modelId}"

    fun modelFor(voice: StudioVoice): CharacterModel? = models.firstOrNull { it.ref == voice.model }
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
    fun installBackend(id: String) {
        if (busy || fileBusy) return
        if (service?.installBridge(id) != true) notify(UiText(R.string.engine_busy))
    }
    fun cancelOfficialDownload() {
        if (officialDownload.running) state.requestId?.let { service?.cancelGeneration(it) }
    }
    fun openVoicePicker() { if (!busy && !fileBusy) choosingVoice = true }
    fun closeVoicePicker() { choosingVoice = false }
    fun chooseVoice(voice: StudioVoice) {
        if (busy || modelFor(voice) == null) return
        select(voice)
        choosingVoice = false
        tab = 0
    }
    fun beginVoice(voice: StudioVoice? = null) {
        if (busy || fileBusy) return
        editorError = null
        draft = if (voice == null) VoiceDraft(modelRef = selectedVoice?.model ?: models.firstOrNull()?.ref)
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
        if (value.name.isBlank() || value.transcript.isBlank() || value.audioPath.isBlank() ||
            value.modelRef == null || models.none { it.ref == value.modelRef }) {
            editorError = UiText(R.string.voice_fields_required)
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
                    val source = File(value.audioPath)
                    require(source.isFile)
                    val previous = store.voices()
                    val old = previous.firstOrNull { it.id == value.id }?.audioPath
                    val target = if (value.audioPath == old) source
                        else File(referenceRoot, "${UUID.randomUUID()}.${source.extension}")
                    if (target != source) {
                        try { source.copyTo(target) }
                        catch (error: Exception) { target.delete(); throw error }
                    }
                    val voice = StudioVoice(value.id, value.name.trim(), value.modelRef,
                        target.absolutePath, value.audioName, value.transcript.trim(), value.durationMs,
                        referenceLanguage = value.referenceLanguage, avatarPath = value.avatarPath)
                    store.save(previous.filterNot { it.id == voice.id } + voice)
                    avatars.save(voice.id, value.avatarPath)
                    old?.let {
                        File(it).takeIf { file -> file != target && file.parentFile == referenceRoot }?.delete()
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
                tab = 0
                notify(UiText(R.string.voice_saved))
            }.onFailure { editorError = UiText(R.string.voice_save_failed) }
        }
    }

    fun deleteVoice(voice: StudioVoice) {
        if (busy || fileBusy || voice.builtIn) return
        audio.stop()
        store.save(store.voices().filterNot { it.id == voice.id })
        avatars.save(voice.id, "")
        File(voice.audioPath).takeIf { it.parentFile == referenceRoot }?.delete()
        voices = voices.filterNot { it.id == voice.id }
        if (selectedId == voice.id) {
            selectedId = voices.firstOrNull()?.id.orEmpty()
            store.selectedId = selectedId
            syncDefaults()
        }
    }

    fun changeSynthesisOptions(value: Map<String, String>) {
        if (busy) return
        val id = selectedVoice?.model?.backendId ?: return
        val library = modelsRepository.provider(id)
        val validated = library.studioOptions(value).filterKeys { key -> library.controls.any { it.name == key } && key in value }
        store.saveOptions(id, validated)
        optionsByBackend = optionsByBackend + (id to validated)
        syncDefaults()
    }

    fun generate() {
        if (!canGenerate) return
        val voice = selectedVoice ?: return
        val model = modelFor(voice) ?: return
        val promptLanguage = ReferenceLanguage.resolve(voice.referenceLanguage, voice.transcript)
        val provider = modelsRepository.provider(model)
        val languageError = provider.languageError(outputLanguage, text) ?: provider.languageError(promptLanguage, voice.transcript)
        if (languageError != null) { generationError = UiText(languageError); return }
        if (!File(voice.audioPath).isFile) {
            generationError = UiText(R.string.reference_missing)
            return
        }
        audio.stop()
        generationError = null
        val request = GenerationRequest(model, outputLanguage,
            promptLanguage = promptLanguage, synthesisText = text.trim(),
            referenceAudioPath = voice.audioPath, referenceText = voice.transcript, auxiliaryReferenceAudioPaths = auxiliaryPaths,
            options = provider.studioOptions(synthesisOptions))
        pendingRequest = request.context
        pendingVoiceName = voiceName(voice, app.resources)
        pendingText = text.trim()
        submitting = true
        if (service?.synthesize(backend, request) != true) {
            submitting = false
            pendingRequest = null
            generationError = UiText(R.string.engine_other_task)
        }
    }

    fun cancel() {
        val id = pendingRequest?.requestId ?: return
        if (service?.cancelGeneration(id) == true) cancelling = true
    }

    private fun syncDefaults() {
        if (busy) return
        val voice = selectedVoice
        val model = voice?.let(::modelFor)
        if (!runtimeReady || voice == null || model == null || availableBackends.isEmpty()) {
            service?.setActiveDefaults(null)
            return
        }
        service?.setActiveDefaults(ActiveDefaults(backend, model.ref, outputLanguage, voice.audioPath,
            voice.transcript, promptLanguage = ReferenceLanguage.resolve(voice.referenceLanguage, voice.transcript),
            options = modelsRepository.provider(model).studioOptions(synthesisOptions), auxiliaryReferenceAudioPaths = auxiliaryPaths), warmupEnabled)
    }

    fun importRuntimePackage(uri: Uri) {
        val backendId = archiveProvider ?: return
        if (busy || fileBusy) return
        importing = true
        importMessage = UiText(R.string.import_resources_progress)
        audio.stop()
        val accepted = service?.importRuntimePackage(backendId, uri) { result ->
            importing = false
            importMessage = result.fold({ UiText(R.string.import_resources_complete) }, {
                Log.e("VoiceStudio", "Resource import failed", it)
                it.userText() ?: UiText(R.string.import_resources_failed)
            })
            refreshResources()
        } == true
        if (!accepted) {
            importing = false
            importMessage = UiText(R.string.import_engine_busy)
        }
    }

    fun beginCheckpointImport() {
        if (busy || fileBusy || !canImportCheckpoints) return
        checkpointDraft = CheckpointDraft()
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
        checkpointDraft = CheckpointDraft(name = voice.name, audioPath = voice.audioPath,
            gptName = model.sourceFilenames["gpt"].orEmpty(), sovitsName = model.sourceFilenames["sovits"].orEmpty(),
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
        checkpointDraft = CheckpointDraft(name = official.name, gptName = model.sourceFilenames["gpt"].orEmpty(),
            sovitsName = model.sourceFilenames["sovits"].orEmpty(), editingRef = model.ref,
            audioPath = voice?.audioPath.orEmpty(), audioName = voice?.audioName.orEmpty(),
            transcript = voice?.transcript.orEmpty(), durationMs = voice?.durationMs ?: 0,
            referenceLanguage = voice?.referenceLanguage ?: "auto", avatarPath = voice?.avatarPath.orEmpty())
        checkpointError = null
        checkpointRecognition = importVariantLabel(official.ref.variant)
        importMessage = null
    }

    fun clearCheckpointReplacement(gpt: Boolean) {
        if (busy || fileBusy) return
        checkpointDraft = checkpointDraft?.let { value ->
            val model = models.firstOrNull { it.ref == value.editingRef }
            if (gpt) value.copy(gpt = null, gptName = model?.sourceFilenames?.get("gpt").orEmpty())
            else value.copy(sovits = null, sovitsName = model?.sourceFilenames?.get("sovits").orEmpty())
        }
        checkpointError = null
        checkpointRecognition = checkpointDraft?.takeIf { it.gpt == null && it.sovits == null }
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

    fun pickCheckpoint(uri: Uri, gpt: Boolean) {
        if (busy || fileBusy || checkpointDraft == null) return
        fileBusy = true
        viewModelScope.launch {
            val filename = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            }
            val extension = if (gpt) ".ckpt" else ".pth"
            if (!filename.endsWith(extension, ignoreCase = true)) {
                checkpointError = UiText(R.string.checkpoint_extension, if (gpt) "GPT" else "SoVITS", extension)
                fileBusy = false
                return@launch
            }
            checkpointDraft?.let { value ->
                checkpointDraft = if (gpt) value.copy(gpt = uri, gptName = filename)
                    else value.copy(sovits = uri, sovitsName = filename,
                        name = value.name.ifBlank { filename.substringBeforeLast('.').take(80) })
            }
            checkpointError = null
            val selected = checkpointDraft
            checkpointRecognition = null
            if (selected?.gpt != null && selected.sovits != null) {
                checkpointRecognition = UiText(R.string.model_identifying)
                checkpointRecognition = withContext(Dispatchers.IO) {
                    runCatching { importProvider?.let { service?.previewCheckpoint(it.id, selected.sovits) } }
                        .fold({ it?.let { importVariantLabel(it) } ?: UiText(R.string.model_identify_on_import) }, { UiText(R.string.model_identify_failed) })
                }
            }
            fileBusy = false
        }
    }

    fun importCheckpoints() {
        val value = checkpointDraft ?: return
        val backendId = importProvider?.id ?: return
        if (busy || fileBusy) return
        val editing = value.editingRef != null
        if (value.name.isBlank() || (!editing && (value.gpt == null || value.sovits == null))) {
            checkpointError = UiText(R.string.checkpoint_fields_required)
            return
        }
        val editedModel = value.editingRef
        if (nameExists(value.name, modelRef = editedModel)) {
            checkpointError = UiText(R.string.voice_name_exists)
            return
        }
        if ((value.gpt == null) != (value.sovits == null)) {
            checkpointError = UiText(R.string.checkpoint_replace_pair)
            return
        }
        if (value.audioPath.isBlank() || value.transcript.isBlank()) {
            checkpointError = UiText(R.string.checkpoint_reference_required)
            return
        }
        importing = true
        checkpointError = null
        importMessage = if (editing && value.gpt == null) UiText(R.string.voice_saving) else UiText(R.string.model_reading)
        audio.stop()
        val reference = ReferenceExample(File(value.audioPath), value.transcript, value.referenceLanguage)
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
                tab = if (editing) 1 else 0
                notify(if (editing) UiText(R.string.voice_updated) else UiText(R.string.voice_imported))
            }.onFailure {
                Log.e("VoiceStudio", "Checkpoint import failed", it)
                checkpointError = it.userText()
                    ?: UiText(R.string.checkpoint_import_failed)
                importMessage = null
                syncDefaults()
            }
        }
        val accepted = if (editing && value.gpt == null) {
            service?.editCharacterReference(requireNotNull(editedModel), value.name, reference, onComplete) == true
        } else {
            service?.importCheckpointPair(backendId, value.name, value.gpt!!, value.sovits!!,
                onProgress = { importMessage = it }, onComplete = onComplete, reference = reference,
                replace = value.editingRef, gptFilename = value.gptName, sovitsFilename = value.sovitsName) == true
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
                app.contentResolver.openOutputStream(uri)?.use { destination ->
                    File(path).inputStream().use { it.copyTo(destination) }
                } ?: errorUi(UiText(R.string.file_write_failed))
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
        draft?.avatarPath?.let(avatars::discard)
        checkpointDraft?.avatarPath?.let(avatars::discard)
        recorder.close()
        service?.removeListener(listener)
        if (bound) app.unbindService(connection)
        audio.close()
    }
}

internal fun OfficialVoice.displayName(resources: android.content.res.Resources): String =
    resources.getString(R.string.official_model_name, UiText(variantLabelKey).resolve(resources))
