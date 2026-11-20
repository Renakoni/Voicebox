package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.moremodels.MingModel
import dev.renakoni.voicebox.moremodels.MingPackageRepository

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.i18n.UiText
import dev.renakoni.voicebox.i18n.userText
import dev.renakoni.voicebox.i18n.requireUi
import dev.renakoni.voicebox.i18n.errorUi

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
import dev.renakoni.voicebox.OfficialModel
import dev.renakoni.voicebox.OfficialModelCatalog
import dev.renakoni.voicebox.OfficialDownloadState
import dev.renakoni.voicebox.api.ActiveDefaults
import dev.renakoni.voicebox.checkpoint.CheckpointImporter
import dev.renakoni.voicebox.checkpoint.CheckpointPreview
import dev.renakoni.voicebox.inference.modelArchitectureText
import dev.renakoni.voicebox.inference.DeviceAccelerationStatus
import dev.renakoni.voicebox.inference.CharacterModel
import dev.renakoni.voicebox.inference.ExecutionBackend
import dev.renakoni.voicebox.inference.GenerationContext
import dev.renakoni.voicebox.inference.GenerationRequest
import dev.renakoni.voicebox.inference.SynthesisOptions
import dev.renakoni.voicebox.inference.GenerationStage
import dev.renakoni.voicebox.inference.ModelAssetRepository
import dev.renakoni.voicebox.inference.RuntimeAssetRepository
import dev.renakoni.voicebox.inference.ReferenceLanguage
import dev.renakoni.voicebox.inference.LanguageNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

data class VoiceDraft(val id: String = UUID.randomUUID().toString(), val name: String = "",
    val modelDirectory: String = "", val audioPath: String = "", val audioName: String = "",
    val transcript: String = "", val durationMs: Long = 0, val referenceLanguage: String = "auto", val avatarPath: String = "")

data class CheckpointDraft(val name: String = "", val gpt: Uri? = null, val sovits: Uri? = null,
    val gptName: String = "", val sovitsName: String = "", val audioPath: String = "",
    val audioName: String = "", val transcript: String = "", val durationMs: Long = 0, val referenceLanguage: String = "auto",
    val editingCharacterId: String? = null, val modelVersion: String = "v2ProPlus", val avatarPath: String = "")

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = StudioStore(app)
    private val avatars = VoiceAvatarStore(app)
    private val modelsRepository = ModelAssetRepository(app)
    private val runtimeRepository = RuntimeAssetRepository(app)
    private val settings = AppSettingsRepository(app)
    private val officialCatalog = OfficialModelCatalog(app)
    private val mingPackage = MingPackageRepository(app)
    val mingDownloadBytes get() = mingPackage.spec.downloadBytes
    val mingSupported get() = mingPackage.supported
    val mingInstalled get() = models.any(MingModel::matches)
    var mingBackendInstalled by mutableStateOf(false); private set
    val mingSelected get() = selectedVoice?.modelDirectory == MingModel.DIRECTORY
    val officialModels = officialCatalog.models
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
    var outputLanguage by mutableStateOf(store.outputLanguage); private set
    var synthesisOptions by mutableStateOf(store.synthesisOptions); private set
    var loading by mutableStateOf(true); private set
    private var runtimeResources by mutableStateOf(false to false)
    val runtimeReady get() = if (mingSelected) mingInstalled else runtimeResources.first &&
        (selectedVoice?.modelDirectory?.startsWith("v5turbo/") != true || runtimeResources.second)
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
    private var availableBackends by mutableStateOf(emptySet<ExecutionBackend>())
    val gpuAvailable get() = ExecutionBackend.WEBGPU in availableBackends
    var gpuEnabled by mutableStateOf(store.gpuEnabled); private set
    var acceleration by mutableStateOf(DeviceAccelerationStatus()); private set
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
    val canGenerate get() = connected && availableBackends.isNotEmpty() && !loading && !busy && !fileBusy && runtimeReady &&
        selectedVoice != null && text.isNotBlank() && modelFor(selectedVoice!!) != null

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
                launch { backendService.deviceAcceleration.status.collect { acceleration = it } }
                backendService.backendCapabilities.collect {
                    availableBackends = it
                    syncDefaults()
                }
            }
            downloadUpdates?.cancel()
            downloadUpdates = viewModelScope.launch { service!!.officialDownload.collect {
                officialDownload = it
                if (it.completed || it.error != null) refreshResources()
            } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            accelerationUpdates?.cancel()
            downloadUpdates?.cancel()
            officialDownload = OfficialDownloadState()
            service = null
            connected = false
            availableBackends = emptySet()
            submitting = false
            state = BackendServiceState()
            generationError = UiText(R.string.service_disconnected)
        }
    }

    init {
        refreshResources()
        runCatching {
            val intent = VoiceBoxBackendService.serviceIntent(app)
            ContextCompat.startForegroundService(app, intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { notify(UiText(R.string.service_start_failed)) }
    }

    private fun refreshResources() {
        resourceUpdates?.cancel()
        resourceUpdates = viewModelScope.launch {
            loading = true
            try {
                val data = withContext(Dispatchers.IO) {
                    val runtimeReady = runtimeRepository.inspect().isComplete
                    val vocoderReady = officialCatalog.vocoderReady(requireNotNull(app.getExternalFilesDir(null)))
                    val available = modelsRepository.discoverCharacterModels()
                        .filter { modelsRepository.inspect(it.modelFiles).isComplete }
                        .filter { model -> officialModels.firstOrNull { it.directory == model.relativeModelDirectory }
                            ?.let { runtimeReady && (it.architecture != "v5turbo" || vocoderReady) } ?: true }
                    val defaults = available.mapNotNull { model ->
                        runCatching { modelsRepository.findReferenceExample(model) }.getOrNull()?.let { reference ->
                            StudioVoice("default:${model.relativeModelDirectory}", model.displayName,
                                model.relativeModelDirectory, reference.audioFile.absolutePath,
                                reference.audioFile.name, reference.referenceText.orEmpty(), builtIn = true, referenceLanguage = reference.language)
                        }
                    }
                    val voices = (defaults + store.voices()).map { voice ->
                        if (voice.durationMs > 0) voice else voice.copy(
                            durationMs = runCatching { duration(voice.audioPath) }.getOrDefault(0))
                    }
                    Triple(available, voices, Triple(runtimeReady, vocoderReady, mingPackage.installed))
                }
                models = data.first
                voices = data.second.map { it.copy(avatarPath = avatars.path(it.id)) }
                runtimeResources = data.third.first to data.third.second
                mingBackendInstalled = data.third.third
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
        outputLanguage = language
        store.outputLanguage = language
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
    fun modelFor(voice: StudioVoice): CharacterModel? = models.firstOrNull { it.relativeModelDirectory == voice.modelDirectory }
    fun modelFor(official: OfficialModel): CharacterModel? = models.firstOrNull { it.relativeModelDirectory == official.directory }
    fun isOfficialDirectory(directory: String) = officialModels.any { it.directory == directory }
    fun voiceFor(official: OfficialModel): StudioVoice? = voices.firstOrNull { it.builtIn && it.modelDirectory == official.directory }
    fun voiceName(voice: StudioVoice, resources: Resources): String =
        officialModels.firstOrNull { voice.builtIn && it.directory == voice.modelDirectory }
            ?.displayName(resources) ?: voice.name
    private fun nameExists(name: String, voiceId: String? = null, modelDirectory: String? = null): Boolean {
        val names = officialModels.filterNot { it.directory == modelDirectory }.map { it.name } +
            models.filterNot { it.relativeModelDirectory == modelDirectory }.map { it.displayName } +
            voices.filterNot { it.id == voiceId || (it.builtIn && it.modelDirectory == modelDirectory) }.map { it.name }
        return names.any { it.trim().equals(name.trim(), ignoreCase = true) }
    }
    fun downloadOfficial(official: OfficialModel) {
        if (busy || fileBusy) return
        if (service?.downloadOfficialModel(official.id) != true) notify(UiText(R.string.engine_busy))
    }
    fun downloadMing() {
        if (busy || fileBusy) return
        if (service?.downloadOfficialModel(MingModel.ID) != true) notify(UiText(R.string.engine_busy))
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
        draft = if (voice == null) VoiceDraft(modelDirectory = selectedVoice?.modelDirectory
            ?: models.firstOrNull()?.relativeModelDirectory.orEmpty())
        else VoiceDraft(voice.id, voice.name, voice.modelDirectory,
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
            models.none { it.relativeModelDirectory == value.modelDirectory }) {
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
                    val voice = StudioVoice(value.id, value.name.trim(), value.modelDirectory,
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

    fun changeSynthesisOptions(value: SynthesisOptions) {
        if (busy) return
        synthesisOptions = value
        store.synthesisOptions = value
        syncDefaults()
    }

    fun generate() {
        if (!canGenerate) return
        val voice = selectedVoice ?: return
        val model = modelFor(voice) ?: return
        val promptLanguage = ReferenceLanguage.resolve(voice.referenceLanguage, voice.transcript)
        val languageError = LanguageNormalizer.scriptError(outputLanguage, text)
            ?: LanguageNormalizer.scriptError(promptLanguage, voice.transcript)
        if (languageError != null) { generationError = languageError; return }
        if (!File(voice.audioPath).isFile) {
            generationError = UiText(R.string.reference_missing)
            return
        }
        audio.stop()
        generationError = null
        val request = GenerationRequest(model, outputLanguage,
            promptLanguage = promptLanguage, synthesisText = text.trim(),
            referenceAudioPath = voice.audioPath, referenceText = voice.transcript, maxDecoderSteps = 1500,
            synthesisOptions = if (mingSelected) SynthesisOptions.Default else synthesisOptions)
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
        if (!warmupEnabled || availableBackends.isEmpty() || busy) return
        val voice = selectedVoice
        val model = voice?.let(::modelFor)
        if (!runtimeReady || voice == null || model == null) {
            service?.setActiveDefaults(null)
            return
        }
        service?.setActiveDefaults(ActiveDefaults(backend, model.id, outputLanguage, voice.audioPath,
            voice.transcript, 1500, promptLanguage = ReferenceLanguage.resolve(voice.referenceLanguage, voice.transcript),
            synthesisOptions = if (mingSelected) SynthesisOptions.Default else synthesisOptions))
    }

    fun importRuntimePackage(uri: Uri) {
        if (busy || fileBusy) return
        importing = true
        importMessage = UiText(R.string.import_resources_progress)
        audio.stop()
        val accepted = service?.importRuntimePackage(uri) { result ->
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
        if (busy || fileBusy) return
        checkpointDraft = CheckpointDraft()
        checkpointError = null
        checkpointRecognition = null
        importMessage = null
    }

    fun beginEditVoice(voice: StudioVoice) {
        if (busy || fileBusy) return
        if (voice.builtIn && voice.modelDirectory == MingModel.DIRECTORY) {
            beginVoice(voice.copy(id = UUID.randomUUID().toString(), name = ""))
            return
        }
        if (!voice.builtIn) { beginVoice(voice); return }
        val model = modelFor(voice) ?: return
        checkpointDraft = CheckpointDraft(name = voice.name, audioPath = voice.audioPath,
            gptName = model.gptFilename, sovitsName = model.sovitsFilename,
            audioName = voice.audioName, transcript = voice.transcript, durationMs = voice.durationMs,
            referenceLanguage = voice.referenceLanguage, editingCharacterId = model.id,
            modelVersion = model.relativeModelDirectory.substringBefore('/'), avatarPath = voice.avatarPath)
        checkpointError = null
        checkpointRecognition = modelArchitectureText(model.relativeModelDirectory.substringBefore('/'))
        importMessage = null
    }

    fun editOfficial(official: OfficialModel) {
        if (busy || fileBusy) return
        val model = modelFor(official) ?: return
        val voice = voiceFor(official)
        checkpointDraft = CheckpointDraft(name = official.name, gptName = model.gptFilename,
            sovitsName = model.sovitsFilename, editingCharacterId = model.id, modelVersion = official.architecture,
            audioPath = voice?.audioPath.orEmpty(), audioName = voice?.audioName.orEmpty(),
            transcript = voice?.transcript.orEmpty(), durationMs = voice?.durationMs ?: 0,
            referenceLanguage = voice?.referenceLanguage ?: "auto", avatarPath = voice?.avatarPath.orEmpty())
        checkpointError = null
        checkpointRecognition = modelArchitectureText(official.architecture)
        importMessage = null
    }

    fun clearCheckpointReplacement(gpt: Boolean) {
        if (busy || fileBusy) return
        checkpointDraft = checkpointDraft?.let { value ->
            val model = models.firstOrNull { it.id == value.editingCharacterId }
            if (gpt) value.copy(gpt = null, gptName = model?.gptFilename.orEmpty())
            else value.copy(sovits = null, sovitsName = model?.sovitsFilename.orEmpty())
        }
        checkpointError = null
        checkpointRecognition = checkpointDraft?.takeIf { it.gpt == null && it.sovits == null }
            ?.let { modelArchitectureText(it.modelVersion) }
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
                    runCatching { CheckpointPreview.read(app, selected.sovits) }
                        .fold({ it?.let { modelArchitectureText(it) } ?: UiText(R.string.model_identify_on_import) }, { UiText(R.string.model_identify_failed) })
                }
            }
            fileBusy = false
        }
    }

    fun importCheckpoints() {
        val value = checkpointDraft ?: return
        if (busy || fileBusy) return
        val editing = value.editingCharacterId != null
        if (value.name.isBlank() || (!editing && (value.gpt == null || value.sovits == null))) {
            checkpointError = UiText(R.string.checkpoint_fields_required)
            return
        }
        val editedDirectory = value.editingCharacterId?.let { "${value.modelVersion}/$it/tts_models" }
        if (nameExists(value.name, modelDirectory = editedDirectory)) {
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
        val reference = CheckpointImporter.Reference(File(value.audioPath), value.transcript, value.referenceLanguage)
        val onComplete: (Result<CheckpointImporter.Result>) -> Unit = { result ->
            importing = false
            result.onSuccess { imported ->
                deleteDraftFile(value.audioPath)
                checkpointDraft = null
                importMessage = null
                selectedId = "default:${imported.architecture}/${imported.characterId}/tts_models"
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
            service?.editCharacterReference(value.editingCharacterId!!, value.name, reference, value.modelVersion, onComplete) == true
        } else {
            service?.importCheckpointPair(value.name, value.gpt!!, value.sovits!!,
                onProgress = { importMessage = it }, onComplete = onComplete, reference = reference,
                replaceCharacterId = value.editingCharacterId, gptFilename = value.gptName, sovitsFilename = value.sovitsName) == true
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
