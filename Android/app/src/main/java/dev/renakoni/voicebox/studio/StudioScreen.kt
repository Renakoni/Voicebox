@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renakoni.voicebox.studio

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalAccessibilityManager
import dev.renakoni.voicebox.core.i18n.UiText
import kotlinx.coroutines.delay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.studio.creation.CreatePage

internal sealed interface StudioDestination

internal enum class StudioTab(val label: Int) : StudioDestination {
    CREATE(R.string.tab_create), VOICES(R.string.tab_voices), MODELS(R.string.models), SETTINGS(R.string.settings),
}

private enum class StudioDetail : StudioDestination {
    PICKER, VOICE_EDITOR, CHECKPOINT_EDITOR, ABOUT,
}

private data class StudioPage(
    val destination: StudioDestination,
    val voice: VoiceDraft? = null,
    val checkpoint: CheckpointDraft? = null,
)

@Composable
fun StudioScreen(vm: StudioViewModel, pickReference: () -> Unit, pickRuntimePackage: () -> Unit,
    saveAudio: (StudioOutput) -> Unit, shareAudio: (StudioOutput) -> Unit,
    sendQqAudio: (StudioOutput) -> Unit, pickCheckpoint: (Boolean) -> Unit = {}, recordReference: () -> Unit = {},
    pickAvatar: () -> Unit = {}, takeAvatarPhoto: () -> Unit = {}) {
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure { vm.notify(UiText(R.string.link_open_failed)) }
    }
    var showingAbout by rememberSaveable { mutableStateOf(false) }
    val closeAbout = { showingAbout = false; vm.tab = StudioTab.SETTINGS }
    val page = when {
        showingAbout -> StudioPage(StudioDetail.ABOUT)
        vm.checkpointDraft != null -> StudioPage(StudioDetail.CHECKPOINT_EDITOR, checkpoint = vm.checkpointDraft)
        vm.draft != null -> StudioPage(StudioDetail.VOICE_EDITOR, voice = vm.draft)
        vm.choosingVoice -> StudioPage(StudioDetail.PICKER)
        else -> StudioPage(vm.tab)
    }
    val notice = vm.notice?.resolve(resources)
    LaunchedEffect(vm.notice, notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    BackHandler(page.destination != StudioTab.CREATE) {
        if (showingAbout) closeAbout()
        else if (vm.recordingMs != null) vm.cancelReferenceRecording()
        else if (vm.checkpointDraft != null) vm.closeCheckpointImport()
        else if (vm.draft != null) vm.closeEditor()
        else if (vm.choosingVoice) vm.closeVoicePicker() else vm.tab = StudioTab.CREATE
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { Column {
            vm.streamPreview?.let {
                StreamPlaybackBar(it, vm::toggleStreamPause, vm::stopStream,
                    if (page.destination is StudioTab) Modifier else Modifier.navigationBarsPadding())
            }
            AnimatedVisibility(page.destination is StudioTab,
                enter = expandVertically(tween(220), expandFrom = Alignment.Bottom) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom) + fadeOut(tween(140))) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        StudioTab.entries.forEach { tab ->
                            NavigationBarItem(selected = vm.tab == tab,
                                onClick = { focus.clearFocus(); vm.tab = tab },
                                icon = {
                                    val size = Modifier.size(22.dp)
                                    when (tab) {
                                        StudioTab.CREATE -> Icon(Icons.Outlined.Edit, null, size)
                                        StudioTab.VOICES -> Icon(Icons.Outlined.Face, null, size)
                                        StudioTab.MODELS -> Icon(painterResource(R.drawable.ic_models), null, size)
                                        StudioTab.SETTINGS -> Icon(Icons.Outlined.Settings, null, size)
                                    }
                                }, label = { Text(resources.getString(tab.label)) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                        }
                    }
                }
            }
        } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            AnimatedContent(targetState = page, contentKey = { it.destination }, modifier = width.fillMaxHeight(),
                transitionSpec = {
                    val direction = (targetState.destination is StudioDetail).compareTo(initialState.destination is StudioDetail)
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { direction * it / 6 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -direction * it / 10 }) using
                        null
                }, label = "studio_navigation") { target ->
                when (target.destination) {
                    StudioDetail.CHECKPOINT_EDITOR -> CheckpointImportPage(vm, requireNotNull(target.checkpoint),
                        pickCheckpoint, pickReference, recordReference, pickAvatar, takeAvatarPhoto, Modifier.fillMaxWidth())
                    StudioDetail.VOICE_EDITOR -> VoiceEditor(vm, requireNotNull(target.voice), pickReference,
                        recordReference, pickAvatar, takeAvatarPhoto, Modifier.fillMaxWidth())
                    StudioDetail.PICKER -> VoicePickerPage(vm, Modifier.fillMaxWidth())
                    StudioTab.CREATE -> CreatePage(vm, saveAudio, shareAudio, sendQqAudio, pickRuntimePackage, Modifier.fillMaxWidth())
                    StudioTab.VOICES -> VoicesPage(vm, Modifier.fillMaxWidth())
                    StudioTab.SETTINGS -> SettingsPage(vm, { showingAbout = true }, { openLink(PROJECT_URL) }, Modifier.fillMaxWidth())
                    StudioTab.MODELS -> ModelsPage(vm, Modifier.fillMaxWidth())
                    StudioDetail.ABOUT -> AboutPage(closeAbout, vm::notify, openLink, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
internal fun PageHeader(title: String) {
    // A plain editorial title rather than an app bar: same 64dp footprint, more presence.
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp).padding(top = 18.dp, bottom = 10.dp),
        contentAlignment = Alignment.BottomStart) {
        Text(title, Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.3).sp))
    }
}

@Composable
private fun VoicePickerPage(vm: StudioViewModel, modifier: Modifier) {
    val resources = LocalResources.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = vm::closeVoicePicker) { Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_create)) }
            Text(resources.getString(R.string.select_voice), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        val voices = vm.voices
        voices.forEachIndexed { index, voice ->
            val available = vm.modelFor(voice) != null
            val name = vm.voiceName(voice, resources)
            val selected = voice.id == vm.selectedId
            VoiceTile(groupedShape(index, voices.size), selected, enabled = available && !vm.busy,
                onClick = { vm.chooseVoice(voice) },
                avatar = { RingedAvatar(name, voice.avatarPath, selected, dim = !available) },
                trailing = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Default.Check, resources.getString(R.string.selected), Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!available) Text(resources.getString(R.string.model_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CheckpointImportPage(vm: StudioViewModel, draft: CheckpointDraft, pickCheckpoint: (Boolean) -> Unit, pickReference: () -> Unit,
    recordReference: () -> Unit, pickAvatar: () -> Unit, takeAvatarPhoto: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val enabled = !vm.busy && !vm.fileBusy
    val editing = draft.editingRef != null
    val officialModel = vm.officialModels.firstOrNull { it.ref == draft.editingRef }
    val official = officialModel != null
    val displayName = officialModel?.displayName(resources) ?: draft.name
    val focus = LocalFocusManager.current
    VoiceEditorLayout(if (editing) resources.getString(R.string.edit_voice) else resources.getString(R.string.import_format_voice, draft.format.title), vm::closeCheckpointImport, enabled, modifier,
        actions = {
            StudioButton(onClick = { focus.clearFocus(); vm.importCheckpoints() }, enabled = enabled && vm.connected,
                style = StudioButtonStyle.Quiet,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (editing) { if (vm.importing) resources.getString(R.string.saving) else resources.getString(R.string.save) }
                    else { if (vm.importing) resources.getString(R.string.importing) else resources.getString(R.string.import_action) })
            }
        }) {
        VoiceAvatarEditor(displayName, draft.avatarPath, enabled, pickAvatar, takeAvatarPhoto)
        OutlinedTextField(displayName, vm::editCheckpointName, label = { Text(resources.getString(R.string.voice_name)) }, singleLine = true,
            enabled = enabled && !official, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        listOf(true, false).forEach { first ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(resources.getString(if (first) draft.format.firstLabel else draft.format.secondLabel), style = MaterialTheme.typography.titleMedium)
                StudioButton(onClick = { pickCheckpoint(first) }, enabled = enabled && !official, style = StudioButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text((if (first) draft.firstName else draft.secondName).ifBlank { if (editing) resources.getString(R.string.original_filename_unknown) else resources.getString(R.string.select_file) },
                        maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (editing && (if (first) draft.first else draft.second) != null) {
                    StudioButton(onClick = { vm.clearCheckpointReplacement(first) }, enabled = enabled,
                        style = StudioButtonStyle.Quiet) { Text(resources.getString(R.string.undo_replacement)) }
                }
            }
        }
        if (draft.format == CheckpointFormat.GPT) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(resources.getString(R.string.model_version), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vm.checkpointRecognition?.resolve(resources) ?: resources.getString(R.string.awaiting_import),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    color = if (vm.checkpointRecognition == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End)
            }
            ReferenceAudioInput(vm, draft.audioPath, draft.audioName, draft.durationMs,
                pickReference, recordReference) {
                Text(resources.getString(R.string.reference_audio), style = MaterialTheme.typography.titleMedium)
            }
            LanguageChoice(resources.getString(R.string.reference_language), draft.referenceLanguage, enabled, vm.importLanguages,
                autoText = draft.transcript, onSelect = vm::editCheckpointLanguage)
            OutlinedTextField(draft.transcript, vm::editCheckpointTranscript, label = { Text(resources.getString(R.string.reference_transcript)) },
                minLines = 3, maxLines = 6,
                enabled = enabled, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth())
        }
        if (vm.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        vm.importMessage?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodyMedium) }
        vm.checkpointError?.let { ErrorMessage(it, vm::clearCheckpointError) }
    }
}

@Composable
private fun VoiceEditor(vm: StudioViewModel, draft: VoiceDraft, pickReference: () -> Unit, recordReference: () -> Unit,
    pickAvatar: () -> Unit, takeAvatarPhoto: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val editing = vm.voices.any { it.id == draft.id }
    val optional = vm.optionalReference(draft.modelRef)
    var confirmDelete by remember { mutableStateOf(false) }
    VoiceEditorLayout(if (editing) resources.getString(R.string.edit_voice) else resources.getString(R.string.clone_voice), vm::closeEditor, !vm.fileBusy, modifier,
        actions = {
            StudioButton(onClick = { focus.clearFocus(); vm.saveVoice() }, enabled = !vm.fileBusy && !vm.busy,
                style = StudioButtonStyle.Quiet,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(resources.getString(R.string.save)) }
        }) {
        VoiceAvatarEditor(draft.name, draft.avatarPath, !vm.fileBusy && !vm.busy, pickAvatar, takeAvatarPhoto)
        OutlinedTextField(draft.name, { vm.edit(draft.copy(name = it)) }, label = { Text(resources.getString(R.string.voice_name)) },
            placeholder = { Text(resources.getString(R.string.voice_name_hint)) }, singleLine = true, enabled = !vm.fileBusy,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepTitle("01", resources.getString(R.string.select_base_voice))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                vm.officialModels.forEach { model ->
                    val installed = vm.modelFor(model)
                    FilterChip(selected = installed != null && draft.modelRef == installed.ref,
                        onClick = { installed?.let { vm.edit(draft.copy(modelRef = it.ref)) } },
                        label = { Text(model.displayName(resources)) }, enabled = installed != null && !vm.fileBusy && !vm.busy)
                }
                vm.cloneModels.filterNot { vm.isOfficial(it.ref) }.forEach { model ->
                    FilterChip(selected = draft.modelRef == model.ref,
                        onClick = { vm.edit(draft.copy(modelRef = model.ref)) },
                        label = { Text(model.displayName) }, enabled = !vm.fileBusy,
                        leadingIcon = if (draft.modelRef == model.ref) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null)
                }
            }
        }
        ReferenceAudioInput(vm, draft.audioPath, draft.audioName, draft.durationMs,
            pickReference, recordReference) {
            StepTitle("02", resources.getString(if (optional) R.string.reference_audio_optional else R.string.reference_audio))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepTitle("03", resources.getString(if (optional) R.string.reference_transcript_optional else R.string.enter_transcript),
                    Modifier.weight(1f))
                val enabled = draft.audioPath.isNotBlank() && draft.audioName.isNotBlank() && !vm.fileBusy && !vm.busy
                StudioButton(onClick = {
                    val transcript = draft.audioName.substringBeforeLast('.').ifEmpty { draft.audioName }
                    vm.edit(draft.copy(transcript = transcript))
                }, enabled = enabled, style = StudioButtonStyle.Quiet) {
                    Text(resources.getString(R.string.use_audio_filename),
                        color = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
            if (!optional) LanguageChoice(resources.getString(R.string.reference_language), draft.referenceLanguage, !vm.fileBusy, vm.languages(draft.modelRef),
                autoText = draft.transcript, onSelect = { vm.edit(draft.copy(referenceLanguage = it)) })
            OutlinedTextField(draft.transcript, { vm.edit(draft.copy(transcript = it)) },
                minLines = 3, maxLines = 6,
                enabled = !vm.fileBusy, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth())
        }
        vm.editorError?.let { ErrorMessage(it, vm::clearEditorError) }
        if (editing) TextButton(onClick = { confirmDelete = true }, enabled = !vm.busy && !vm.fileBusy) {
            Text(resources.getString(R.string.delete_voice), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(resources.getString(R.string.delete_voice_confirmation, draft.name)) },
        confirmButton = { TextButton(onClick = {
            vm.voices.firstOrNull { it.id == draft.id }?.let(vm::deleteVoice)
            confirmDelete = false
            vm.closeEditor()
        }) { Text(resources.getString(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(resources.getString(R.string.keep)) } })
}

@Composable
private fun VoiceEditorLayout(title: String, onBack: () -> Unit, backEnabled: Boolean, modifier: Modifier,
    actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val resources = LocalResources.current
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_voices)) }
            }, actions = actions, windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
private fun StepTitle(number: String, title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
internal fun ErrorMessage(message: UiText, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(message) {
        withFrameNanos { }
        bringIntoView.bringIntoView()
    }
    DismissErrorAfterTimeout(message, onDismiss)
    Surface(modifier = Modifier.bringIntoViewRequester(bringIntoView),
        color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
        Text(message.resolve(resources), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DismissErrorAfterTimeout(message: UiText, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 10_000L, containsText = true,
    ) ?: 10_000L
    LaunchedEffect(message, timeout) {
        delay(timeout)
        dismiss()
    }
}
