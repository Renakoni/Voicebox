package dev.rinstel.genie_tts.studio

import android.app.Application
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
import dev.rinstel.genie_tts.AppSettingsRepository
import dev.rinstel.genie_tts.BackendServiceListener
import dev.rinstel.genie_tts.BackendServiceState
import dev.rinstel.genie_tts.GenieBackendService
import dev.rinstel.genie_tts.ReferenceAudioPicker
import dev.rinstel.genie_tts.OfficialModel
import dev.rinstel.genie_tts.OfficialModelCatalog
import dev.rinstel.genie_tts.OfficialDownloadState
import dev.rinstel.genie_tts.api.ActiveDefaults
import dev.rinstel.genie_tts.checkpoint.CheckpointImporter
import dev.rinstel.genie_tts.checkpoint.CheckpointPreview
import dev.rinstel.genie_tts.inference.modelArchitectureLabel
import dev.rinstel.genie_tts.inference.DeviceAccelerationStatus
import dev.rinstel.genie_tts.inference.CharacterModel
import dev.rinstel.genie_tts.inference.ExecutionBackend
import dev.rinstel.genie_tts.inference.GenerationContext
import dev.rinstel.genie_tts.inference.GenerationRequest
import dev.rinstel.genie_tts.inference.SynthesisOptions
import dev.rinstel.genie_tts.inference.GenerationStage
import dev.rinstel.genie_tts.inference.ModelAssetRepository
import dev.rinstel.genie_tts.inference.RuntimeAssetRepository
import dev.rinstel.genie_tts.inference.ReferenceLanguage
import dev.rinstel.genie_tts.inference.LanguageNormalizer
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
    val officialModels = officialCatalog.models
    private val referenceRoot = File(app.filesDir, "studio_references").apply { mkdirs() }
    private var service: GenieBackendService? = null
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
    val runtimeReady get() = runtimeResources.first &&
        (selectedVoice?.modelDirectory?.startsWith("v5turbo/") != true || runtimeResources.second)
    var connected by mutableStateOf(false); private set
    var state by mutableStateOf(BackendServiceState()); private set
    var draft by mutableStateOf<VoiceDraft?>(null); private set
    var checkpointDraft by mutableStateOf<CheckpointDraft?>(null); private set
    var checkpointError by mutableStateOf<String?>(null); private set
    var checkpointRecognition by mutableStateOf<String?>(null); private set
    var officialDownload by mutableStateOf(OfficialDownloadState()); private set
    var choosingVoice by mutableStateOf(false); private set
    var fileBusy by mutableStateOf(false); private set
    var recordingMs by mutableStateOf<Long?>(null); private set
    var importing by mutableStateOf(false); private set
    var importMessage by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var editorError by mutableStateOf<String?>(null); private set
    var generationError by mutableStateOf<String?>(null); private set
    var cancelling by mutableStateOf(false); private set
    var submitting by mutableStateOf(false); private set
    var output by mutableStateOf(store.output?.takeIf { File(it.path).isFile }); private set
    var preview by mutableStateOf(PreviewState()); private set
    var gpuAvailable by mutableStateOf(false); private set
    var gpuEnabled by mutableStateOf(store.gpuEnabled); private set
    var acceleration by mutableStateOf(DeviceAccelerationStatus()); private set
    var deviceAccelerationEnabled by mutableStateOf(settings.deviceAccelerationEnabled); private set
    var theme by mutableStateOf(settings.themeMode); private set
    var apiEnabled by mutableStateOf(settings.apiEnabled); private set
    var apiPort by mutableStateOf(settings.apiPort.toString()); private set
    var apiPortError by mutableStateOf<String?>(null); private set

    val audio = AudioPreview(app, { preview = it }, { notify("暂时无法播放，请检查音频或稍后重试。") })
    private val recorder = ReferenceRecorder(app, referenceRoot, { recordingMs = it }, ::finishReferenceRecording) {
        cancelReferenceRecording()
        referenceRecordingError("录音中断，请重试。")
    }
    val selectedVoice get() = voices.firstOrNull { it.id == selectedId }
    val busy get() = state.busy || submitting || importing || officialDownload.running
    val backend get() = if (gpuAvailable && gpuEnabled) ExecutionBackend.WEBGPU else ExecutionBackend.CPU
    val canGenerate get() = connected && !loading && !busy && !fileBusy && runtimeReady &&
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
                        "这次没有生成有效语音，请检查参考原文，或换一段文字再试。"
                    else "生成没有完成。请检查音色的参考音频和原文；若仍然失败，可在设置中关闭 GPU 加速后重试。"
                }
            }
        }
        val path = next.latestOutputFilePath
        if (next.stage == GenerationStage.COMPLETED && path != null && path != handledOutput) {
            handledOutput = path
            val name = if (ownRequest) pendingVoiceName else "生成语音"
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
            service = (binder as GenieBackendService.LocalBinder).getService()
            connected = true
            gpuAvailable = ExecutionBackend.WEBGPU in service!!.supportedBackends()
            accelerationUpdates?.cancel()
            val detector = service!!.deviceAcceleration
            accelerationUpdates = viewModelScope.launch { detector.status.collect { acceleration = it } }
            downloadUpdates?.cancel()
            downloadUpdates = viewModelScope.launch { service!!.officialDownload.collect {
                officialDownload = it
                if (it.completed || it.error != null) refreshResources()
            } }
            handledOutput = output?.path
            service!!.addListener(listener)
            syncDefaults()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            accelerationUpdates?.cancel()
            downloadUpdates?.cancel()
            officialDownload = OfficialDownloadState()
            service = null
            connected = false
            submitting = false
            state = BackendServiceState()
            generationError = "语音服务已断开，重新打开应用后可重试。"
        }
    }

    init {
        refreshResources()
        runCatching {
            val intent = GenieBackendService.serviceIntent(app)
            ContextCompat.startForegroundService(app, intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { notify("语音服务启动失败，请重新打开应用。") }
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
                    Triple(available, voices, runtimeReady to vocoderReady)
                }
                models = data.first
                voices = data.second.map { it.copy(avatarPath = avatars.path(it.id)) }
                runtimeResources = data.third
                if (voices.none { it.id == selectedId }) {
                    selectedId = voices.firstOrNull()?.id.orEmpty()
                    store.selectedId = selectedId
                }
                syncDefaults()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e("VoiceStudio", "Cannot load voices", error)
                notify("音色加载失败，请检查本地资源后重试。")
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
    fun isOfficialCharacter(id: String?) = officialModels.any { it.id == id }
    fun voiceFor(official: OfficialModel): StudioVoice? = voices.firstOrNull { it.builtIn && it.modelDirectory == official.directory }
    private fun nameExists(name: String, voiceId: String? = null, modelDirectory: String? = null): Boolean {
        val names = officialModels.filterNot { it.directory == modelDirectory }.map { it.name } +
            models.filterNot { it.relativeModelDirectory == modelDirectory }.map { it.displayName } +
            voices.filterNot { it.id == voiceId || (it.builtIn && it.modelDirectory == modelDirectory) }.map { it.name }
        return names.any { it.trim().equals(name.trim(), ignoreCase = true) }
    }
    fun downloadOfficial(official: OfficialModel) {
        if (busy || fileBusy) return
        if (service?.downloadOfficialModel(official.id) != true) notify("引擎正在忙碌，请稍后再试。")
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
            }.onFailure { referenceRecordingError("无法读取这张图片，请重新选择。") }
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
                        require(milliseconds in 500..30_000) { "请选择 0.5 至 30 秒的音频，建议使用清晰的 3 至 10 秒人声。" }
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
                val message = if (it is IllegalArgumentException && it.message?.startsWith("请选择") == true)
                    it.message else "无法读取这段音频，请选择可播放的 WAV、MP3 或 M4A 文件。"
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

    fun referenceRecordingError(message: String?) {
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
        }.onFailure { referenceRecordingError("无法开始录音，请检查麦克风权限和占用情况。") }
    }

    fun finishReferenceRecording() {
        if (recordingMs == null) return
        val forCheckpoint = checkpointDraft != null
        recordingMs = null
        val recorded = runCatching { recorder.finish() }.getOrElse {
            fileBusy = false
            referenceRecordingError("录音太短或未完成，请重新录制。")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                duration(recorded.absolutePath).also { require(it in 500..30_500) }
            } }
            result.onSuccess { applyReference(forCheckpoint, recorded.absolutePath, "录音.m4a", it) }
                .onFailure { recorded.delete(); referenceRecordingError("请录制至少半秒的人声。") }
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
            editorError = "请填好音色名称、参考音频和对应原文，并选择基础音色。"
            return
        }
        if (nameExists(value.name, voiceId = value.id)) {
            editorError = "音色名称已存在，请换一个名称。"
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
                notify("音色已保存，可以开始创作了。")
            }.onFailure { editorError = "音色保存失败，请检查手机剩余空间后重试。" }
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
            generationError = "参考音频已丢失，请在音色页重新选择。"
            return
        }
        audio.stop()
        generationError = null
        val request = GenerationRequest(model, outputLanguage,
            promptLanguage = promptLanguage, synthesisText = text.trim(),
            referenceAudioPath = voice.audioPath, referenceText = voice.transcript, maxDecoderSteps = 1500,
            synthesisOptions = synthesisOptions)
        pendingRequest = request.context
        pendingVoiceName = voice.name
        pendingText = text.trim()
        submitting = true
        if (service?.synthesize(backend, request) != true) {
            submitting = false
            pendingRequest = null
            generationError = "引擎正在处理其他任务，请稍后重试。"
        }
    }

    fun cancel() {
        val id = pendingRequest?.requestId ?: return
        if (service?.cancelGeneration(id) == true) cancelling = true
    }

    private fun syncDefaults() {
        if (!warmupEnabled || busy) return
        val voice = selectedVoice
        val model = voice?.let(::modelFor)
        if (!runtimeReady || voice == null || model == null) {
            service?.setActiveDefaults(null)
            return
        }
        service?.setActiveDefaults(ActiveDefaults(backend, model.id, outputLanguage, voice.audioPath,
            voice.transcript, 1500, promptLanguage = ReferenceLanguage.resolve(voice.referenceLanguage, voice.transcript),
            synthesisOptions = synthesisOptions))
    }

    fun importRuntimePackage(uri: Uri) {
        if (busy || fileBusy) return
        importing = true
        importMessage = "正在导入，完成后即可使用。大文件需要一些时间。"
        audio.stop()
        val accepted = service?.importRuntimePackage(uri) { result ->
            importing = false
            importMessage = result.fold({ "导入完成，资源已保存在手机上。" }, {
                Log.e("VoiceStudio", "Resource import failed", it)
                "导入失败，请选择对应的 Genie ZIP 资源包，并确认手机剩余空间充足。"
            })
            refreshResources()
        } == true
        if (!accepted) {
            importing = false
            importMessage = "引擎正在忙碌，请稍后重新导入。"
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
        if (!voice.builtIn) { beginVoice(voice); return }
        val model = modelFor(voice) ?: return
        checkpointDraft = CheckpointDraft(name = voice.name, audioPath = voice.audioPath,
            gptName = model.gptFilename, sovitsName = model.sovitsFilename,
            audioName = voice.audioName, transcript = voice.transcript, durationMs = voice.durationMs,
            referenceLanguage = voice.referenceLanguage, editingCharacterId = model.id,
            modelVersion = model.relativeModelDirectory.substringBefore('/'), avatarPath = voice.avatarPath)
        checkpointError = null
        checkpointRecognition = modelArchitectureLabel(model.relativeModelDirectory.substringBefore('/'))
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
        checkpointRecognition = modelArchitectureLabel(official.architecture)
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
            ?.let { modelArchitectureLabel(it.modelVersion) }
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
                checkpointError = "${if (gpt) "GPT" else "SoVITS"} 权重请选择以 $extension 结尾的文件。"
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
                checkpointRecognition = "正在识别…"
                checkpointRecognition = withContext(Dispatchers.IO) {
                    runCatching { CheckpointPreview.read(app, selected.sovits) }
                        .fold({ it?.let(::modelArchitectureLabel) ?: "导入时识别" }, { "无法识别，请检查权重文件" })
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
            checkpointError = "请填写音色名称，并选择配套的 .ckpt 和 .pth 文件。"
            return
        }
        val editedDirectory = value.editingCharacterId?.let { "${value.modelVersion}/$it/tts_models" }
        if (nameExists(value.name, modelDirectory = editedDirectory)) {
            checkpointError = "音色名称已存在，请换一个名称。"
            return
        }
        if ((value.gpt == null) != (value.sovits == null)) {
            checkpointError = "更换权重时，请同时选择配套的 .ckpt 和 .pth。"
            return
        }
        if (value.audioPath.isBlank() || value.transcript.isBlank()) {
            checkpointError = "请选择配套的参考音频，并填写音频中的原文。"
            return
        }
        importing = true
        checkpointError = null
        importMessage = if (editing && value.gpt == null) "正在保存音色" else "正在读取模型"
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
                notify(if (editing) "音色已更新。" else "音色已导入，可以开始创作了。")
            }.onFailure {
                Log.e("VoiceStudio", "Checkpoint import failed", it)
                checkpointError = it.message?.takeIf { message -> message.any { c -> c in '\u4e00'..'\u9fff' } }
                    ?: "导入失败，请检查文件是否完整、是否为支持的模型版本，并确认存储空间充足。"
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
            checkpointError = "引擎正在忙碌，请稍后重新导入。"
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
        ContextCompat.startForegroundService(app, GenieBackendService.serviceIntent(app)
            .setAction(GenieBackendService.ACTION_RELOAD_INFERENCE_SETTINGS))
    }
    fun changeTheme(value: AppSettingsRepository.ThemeMode) { theme = value; settings.themeMode = value }
    fun setApi(enabled: Boolean) {
        apiEnabled = enabled
        settings.apiEnabled = enabled
        ContextCompat.startForegroundService(app, GenieBackendService.serviceIntent(app))
    }
    fun editPort(value: String) { apiPort = value; apiPortError = null }
    fun applyPort() {
        val port = apiPort.toIntOrNull()
        if (port == null || port !in 1024..65535) { apiPortError = "端口应为 1024 至 65535。"; return }
        settings.apiPort = port
        ContextCompat.startForegroundService(app, GenieBackendService.serviceIntent(app))
        notify("端口已更新。")
    }
    fun export(uri: Uri, path: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { destination ->
                    File(path).inputStream().use { it.copyTo(destination) }
                } ?: error("无法写入文件")
            }
        }
        notify(if (result.isSuccess) "音频已保存。" else "保存失败，请重新选择保存位置。")
    }
    fun notify(message: String) { notice = message }
    fun clearNotice() { notice = null }

    private fun deleteDraftFile(path: String) {
        File(path).takeIf { it.parentFile == referenceRoot && it.name.startsWith("draft-") }?.delete()
    }
    private fun duration(path: String): Long = MediaMetadataRetriever().use {
        it.setDataSource(path)
        require(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: error("无法读取音频时长")
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
