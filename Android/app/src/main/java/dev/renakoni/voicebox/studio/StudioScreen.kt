@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renakoni.voicebox.studio

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalAccessibilityManager
import android.content.res.Resources
import dev.renakoni.voicebox.i18n.UiText
import kotlinx.coroutines.delay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import dev.renakoni.voicebox.AppSettingsRepository.ThemeMode
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.OfficialModel
import dev.renakoni.voicebox.inference.GenerationStage
import dev.renakoni.voicebox.inference.ReferenceLanguage
import dev.renakoni.voicebox.inference.LanguageNormalizer
import dev.renakoni.voicebox.inference.modelArchitectureLabel
import dev.renakoni.voicebox.inference.SynthesisOptions
import java.util.Locale
import kotlin.math.roundToInt

private enum class StudioDestination(val depth: Int) {
    CREATE(0), VOICES(0), SETTINGS(0), PICKER(1), VOICE_EDITOR(1), CHECKPOINT_EDITOR(1), ABOUT(1), MORE_MODELS(1),
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
    var showingAbout by rememberSaveable { mutableStateOf(false) }
    var showingMoreModels by rememberSaveable { mutableStateOf(false) }
    val closeAbout = { showingAbout = false; vm.tab = 2 }
    val page = when {
        showingMoreModels -> StudioPage(StudioDestination.MORE_MODELS)
        showingAbout -> StudioPage(StudioDestination.ABOUT)
        vm.checkpointDraft != null -> StudioPage(StudioDestination.CHECKPOINT_EDITOR, checkpoint = vm.checkpointDraft)
        vm.draft != null -> StudioPage(StudioDestination.VOICE_EDITOR, voice = vm.draft)
        vm.choosingVoice -> StudioPage(StudioDestination.PICKER)
        vm.tab == 1 -> StudioPage(StudioDestination.VOICES)
        vm.tab == 2 -> StudioPage(StudioDestination.SETTINGS)
        else -> StudioPage(StudioDestination.CREATE)
    }
    val notice = vm.notice?.resolve(resources)
    LaunchedEffect(vm.notice, notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    BackHandler(showingMoreModels || showingAbout || vm.draft != null || vm.checkpointDraft != null || vm.choosingVoice || vm.tab != 0) {
        if (showingMoreModels) showingMoreModels = false
        else if (showingAbout) closeAbout()
        else if (vm.recordingMs != null) vm.cancelReferenceRecording()
        else if (vm.checkpointDraft != null) vm.closeCheckpointImport()
        else if (vm.draft != null) vm.closeEditor()
        else if (vm.choosingVoice) vm.closeVoicePicker() else vm.tab = 0
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(page.destination.depth == 0,
                enter = expandVertically(tween(220), expandFrom = Alignment.Bottom) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom) + fadeOut(tween(140))) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        val labels = listOf(resources.getString(R.string.tab_create), resources.getString(R.string.tab_voices), resources.getString(R.string.settings))
                        val icons = listOf(Icons.Outlined.Edit, Icons.Outlined.Face, Icons.Outlined.Settings)
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(selected = vm.tab == index,
                                onClick = { focus.clearFocus(); vm.tab = index },
                                icon = { Icon(icons[index], contentDescription = null, modifier = Modifier.size(22.dp)) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.testTag("tab_$index"))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            AnimatedContent(targetState = page, contentKey = { it.destination }, modifier = width.fillMaxHeight(),
                transitionSpec = {
                    val direction = targetState.destination.depth.compareTo(initialState.destination.depth)
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { direction * it / 6 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -direction * it / 10 }) using
                        null
                }, label = "studio_navigation") { target ->
                when (target.destination) {
                    StudioDestination.CHECKPOINT_EDITOR -> CheckpointImportPage(vm, requireNotNull(target.checkpoint),
                        pickCheckpoint, pickReference, recordReference, pickAvatar, takeAvatarPhoto, Modifier.fillMaxWidth())
                    StudioDestination.VOICE_EDITOR -> VoiceEditor(vm, requireNotNull(target.voice), pickReference,
                        recordReference, pickAvatar, takeAvatarPhoto, Modifier.fillMaxWidth())
                    StudioDestination.PICKER -> VoicePickerPage(vm, Modifier.fillMaxWidth())
                    StudioDestination.CREATE -> CreatePage(vm, saveAudio, shareAudio, sendQqAudio, pickRuntimePackage, Modifier.fillMaxWidth())
                    StudioDestination.VOICES -> VoicesPage(vm, Modifier.fillMaxWidth())
                    StudioDestination.SETTINGS -> SettingsPage(vm, { showingAbout = true }, { showingMoreModels = true }, Modifier.fillMaxWidth())
                    StudioDestination.MORE_MODELS -> MoreModelsPage(vm, { showingMoreModels = false }, Modifier.fillMaxWidth())
                    StudioDestination.ABOUT -> AboutPage(closeAbout, vm::notify, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String) {
    TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() }) }, windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
private fun CreatePage(vm: StudioViewModel, save: (StudioOutput) -> Unit, share: (StudioOutput) -> Unit,
    sendQq: (StudioOutput) -> Unit, pickRuntime: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    if (vm.voices.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (!vm.loading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(resources.getString(R.string.first_voice), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    StudioButton(onClick = { vm.tab = 1 }) { Text(resources.getString(R.string.go_voices)) }
                }
            }
        }
        return
    }
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxHeight()) {
        PageHeader(resources.getString(R.string.create_title))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp)) {
            if (vm.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(resources.getString(R.string.voices_loading), modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!vm.runtimeReady) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(resources.getString(R.string.engine_setup), style = MaterialTheme.typography.titleMedium)
                    Text(if (vm.selectedVoice?.modelDirectory?.startsWith("v5turbo/") == true)
                        resources.getString(R.string.engine_v5_resources)
                        else resources.getString(R.string.engine_resources), style = MaterialTheme.typography.bodyMedium)
                    StudioButton(onClick = pickRuntime, enabled = vm.connected && !vm.busy) { Text(resources.getString(R.string.import_resources)) }
                    vm.importMessage?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val voice = vm.selectedVoice
                Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !vm.busy, onClick = vm::openVoicePicker)
                    .heightIn(min = 56.dp).testTag("selected_voice"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (voice != null) {
                        VoiceAvatar(vm.voiceName(voice, resources), voice.avatarPath, Modifier.size(36.dp))
                        Text(vm.voiceName(voice, resources), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    } else Text(resources.getString(R.string.select_voice), modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ArrowDropDown, resources.getString(R.string.change_voice), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LanguageMenu(resources.getString(R.string.output_language), vm.outputLanguage, !vm.busy, "output_language", onSelect = vm::changeOutputLanguage)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val writingStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp)
            BasicTextField(value = vm.text, onValueChange = vm::updateText, enabled = !vm.busy,
                textStyle = writingStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), minLines = 4, maxLines = 10,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp)
                    .testTag("synthesis_text").semantics { contentDescription = resources.getString(R.string.speech_text) },
                decorationBox = { inner ->
                    Box {
                        if (vm.text.isEmpty()) Text(resources.getString(R.string.speech_text_hint), style = writingStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                })
            if (!vm.mingSelected) AdvancedSynthesisSettings(vm)
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Text(resources.getString(R.string.character_count, vm.text.length), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StudioButton(onClick = { vm.updateText(resources.getString(when (vm.outputLanguage) {
                    "ja" -> R.string.speech_sample_ja
                    "en" -> R.string.speech_sample_en
                    else -> R.string.speech_sample_zh
                })) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet) {
                    Text(resources.getString(R.string.sample_text))
                }
                StudioButton(onClick = { focus.clearFocus(); vm.generate() }, enabled = vm.canGenerate,
                    modifier = Modifier.testTag("generate")) {
                    Text(resources.getString(R.string.generate_speech))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            if (vm.busy) {
                val progress = generationStatus(vm.state, resources)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (vm.importing) resources.getString(R.string.resources_importing) else if (vm.cancelling) resources.getString(R.string.stopping) else progress.title,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("generation_status"))
                        if (!vm.importing) StudioButton(onClick = vm::cancel, enabled = !vm.cancelling,
                            style = StudioButtonStyle.Quiet) { Text(resources.getString(R.string.stop)) }
                    }
                    if (!vm.importing && !vm.cancelling) progress.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("generation_detail"))
                    }
                    if (progress.percent != null && !vm.importing && !vm.cancelling) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (vm.state.stage == GenerationStage.CANCELLED) {
                Text(resources.getString(R.string.generation_stopped), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.generationError?.let { ErrorMessage(it, vm::clearGenerationError) }
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val output = vm.output
            if (output != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(resources.getString(R.string.latest_audio), style = MaterialTheme.typography.titleMedium)
                    Text(output.voiceName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    if (output.text.isNotBlank() && output.text.trim() != vm.text.trim()) {
                        Text(resources.getString(R.string.text_changed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutputPlayer(output, vm, save, share, sendQq)
            } else {
                Text(resources.getString(R.string.latest_audio), style = MaterialTheme.typography.titleMedium)
                Text(resources.getString(R.string.no_generated_audio), modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AdvancedSynthesisSettings(vm: StudioViewModel) {
    val resources = LocalResources.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Keep drag previews out of requests; busy/collapse restores the committed values.
    var options by remember(vm.synthesisOptions, vm.busy, expanded) { mutableStateOf(vm.synthesisOptions) }
    val commit = { vm.changeSynthesisOptions(options) }
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp)
        .testTag("advanced_settings").semantics { stateDescription = if (expanded) resources.getString(R.string.expanded) else resources.getString(R.string.collapsed) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(resources.getString(R.string.advanced_settings), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val drawingInset = 24.dp
    AnimatedVisibility(expanded, enter = expandVertically(tween(180)) + fadeIn(tween(150)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(150)),
        modifier = Modifier.layout { measurable, constraints ->
            // Include the page margins in the animation clip so endpoint thumbs and ripples fit.
            val inset = drawingInset.roundToPx()
            val placeable = measurable.measure(constraints.offset(horizontal = inset * 2))
            layout(placeable.width - inset * 2, placeable.height) { placeable.placeRelative(-inset, 0) }
        }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = drawingInset).padding(bottom = 8.dp)) {
            fun number(value: Float) = String.format(Locale.ROOT, "%.2f", value)
            SynthesisSlider(resources.getString(R.string.speech_speed), "${number(options.speed.toFloat())}×", options.speed.toFloat(), 0.6f..1.65f, 20, !vm.busy,
                "synthesis_speed", { options = options.copy(speed = (it * 20).roundToInt() / 20.0) }, commit)
            SynthesisSlider(resources.getString(R.string.temperature), number(options.temperature), options.temperature, 0f..1f, 99, !vm.busy,
                "synthesis_temperature", { options = options.copy(temperature = (it * 100).roundToInt() / 100f) }, commit)
            SynthesisSlider("Top-K", options.topK.toString(), options.topK.toFloat(), 1f..100f, 98, !vm.busy,
                "synthesis_top_k", { options = options.copy(topK = it.roundToInt()) }, commit)
            SynthesisSlider("Top-P", number(options.topP), options.topP, 0f..1f, 99, !vm.busy,
                "synthesis_top_p", { options = options.copy(topP = (it * 100).roundToInt() / 100f) }, commit)
            SynthesisSlider(resources.getString(R.string.repetition_penalty), number(options.repetitionPenalty), options.repetitionPenalty, 1f..2f, 99, !vm.busy,
                "synthesis_repetition_penalty", { options = options.copy(repetitionPenalty = (it * 100).roundToInt() / 100f) }, commit)
            StudioButton(onClick = { options = SynthesisOptions.Default; commit() }, enabled = !vm.busy && options != SynthesisOptions.Default,
                style = StudioButtonStyle.Quiet, modifier = Modifier.align(Alignment.End).testTag("reset_synthesis_options")) {
                Text(resources.getString(R.string.reset_defaults))
            }
        }
    }
}

@Composable
private fun SynthesisSlider(label: String, displayValue: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, enabled: Boolean, tag: String, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(displayValue, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StudioSlider(value, onChange, enabled = enabled, valueRange = range, steps = steps, onValueChangeFinished = onFinished,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
                contentDescription = label
                stateDescription = displayValue
            })
    }
}

private fun languageName(resources: Resources, value: String) = when (LanguageNormalizer.normalize(value)) {
    "japanese" -> resources.getString(R.string.language_japanese)
    "english" -> resources.getString(R.string.language_english)
    else -> resources.getString(R.string.language_chinese)
}

@Composable
private fun LanguageChoice(title: String, value: String, enabled: Boolean, tag: String,
    autoText: String? = null, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LanguageMenu(title, value, enabled, tag, autoText, onSelect)
    }
}

@Composable
private fun LanguageMenu(title: String, value: String, enabled: Boolean, tag: String,
    autoText: String? = null, onSelect: (String) -> Unit) {
    val resources = LocalResources.current
    var expanded by remember { mutableStateOf(false) }
    val label = if (value == "auto") resources.getString(R.string.language_auto, languageName(resources, ReferenceLanguage.resolve(value, autoText.orEmpty())))
        else languageName(resources, value)
    Box {
        StudioButton(onClick = { expanded = true }, enabled = enabled, style = StudioButtonStyle.Quiet,
            modifier = Modifier.testTag(tag).semantics { contentDescription = resources.getString(R.string.language_selection_description, title, label) }) {
            Text(label)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (autoText != null) DropdownMenuItem(text = { Text(resources.getString(R.string.language_from_transcript)) },
                onClick = { onSelect("auto"); expanded = false }, modifier = Modifier.testTag("${tag}_auto"))
            listOf("zh", "ja", "en").forEach { language ->
                DropdownMenuItem(text = { Text(languageName(resources, language)) },
                    onClick = { onSelect(language); expanded = false }, modifier = Modifier.testTag("${tag}_$language"))
            }
        }
    }
}

@Composable
private fun OutputPlayer(output: StudioOutput, vm: StudioViewModel, save: (StudioOutput) -> Unit,
    share: (StudioOutput) -> Unit, sendQq: (StudioOutput) -> Unit) {
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (output.text.isNotBlank())
            Text(output.text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutputPlayback(vm.preview.takeIf { it.path == output.path } ?: PreviewState(), output.durationMs, !vm.busy,
            onToggle = { vm.audio.toggle(output.path) }, onSeek = vm.audio::seek)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StudioButton(onClick = { save(output) }, style = StudioButtonStyle.Quiet) { Text(resources.getString(R.string.save_audio)) }
            StudioButton(onClick = { share(output) }, style = StudioButtonStyle.Quiet) { Text(resources.getString(R.string.share)) }
            StudioButton(onClick = { sendQq(output) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet,
                modifier = Modifier.testTag("send_qq_voice")) {
                Text(resources.getString(R.string.send_qq))
            }
        }
    }
}

@Composable
private fun VoicesPage(vm: StudioViewModel, modifier: Modifier) {
    val resources = LocalResources.current
    Column(modifier.fillMaxHeight()) {
        PageHeader(resources.getString(R.string.my_voices))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StudioButton(onClick = { vm.beginVoice() }, enabled = !vm.loading && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Secondary, modifier = Modifier.weight(1f).fillMaxHeight().testTag("new_voice")) {
                Text(resources.getString(R.string.clone_voice))
            }
            StudioButton(onClick = vm::beginCheckpointImport, enabled = vm.connected && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Secondary, modifier = Modifier.weight(1f).fillMaxHeight().testTag("import_voice")) {
                Text(resources.getString(R.string.import_voice))
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            vm.officialModels.forEach { official ->
                item(key = official.id) { OfficialVoiceRow(vm, official) }
            }
            val otherVoices = vm.voices.filterNot { voice -> voice.builtIn && vm.isOfficialDirectory(voice.modelDirectory) }
            itemsIndexed(otherVoices, key = { _, voice -> voice.id }) { index, voice ->
                val available = vm.modelFor(voice) != null
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !vm.busy && available) { vm.select(voice); vm.tab = 0 }
                            .padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            VoiceAvatar(voice.name, voice.avatarPath)
                            Column(Modifier.weight(1f)) {
                                Text(voice.name, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(modelArchitectureLabel(resources, voice.modelDirectory.substringBefore('/')),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!available) Text(resources.getString(R.string.model_unavailable),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (vm.selectedId == voice.id) Icon(Icons.Default.Check, resources.getString(R.string.selected),
                                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { vm.beginEditVoice(voice) }, enabled = !vm.busy && !vm.fileBusy,
                            modifier = Modifier.testTag("edit_voice_${voice.id}")) {
                            Icon(Icons.Default.Edit, resources.getString(R.string.edit_named_voice, voice.name), modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (index < otherVoices.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun OfficialVoiceRow(vm: StudioViewModel, model: OfficialModel) {
    val resources = LocalResources.current
    val ready = vm.modelFor(model) != null
    val voice = vm.voiceFor(model)
    val status = vm.officialDownload.takeIf { it.modelId == model.id }
    val downloading = status?.running == true
    Column {
        Row(Modifier.fillMaxWidth().alpha(if (ready) 1f else .55f).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = ready && !vm.busy && !vm.fileBusy) {
                    if (voice != null) { vm.select(voice); vm.tab = 0 } else vm.editOfficial(model)
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceAvatar(model.displayName(resources), voice?.avatarPath.orEmpty(), Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.displayName(resources), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                    val detail = when {
                        downloading -> status.label?.resolve(resources).orEmpty() + (status.percent?.let { " $it%" } ?: "")
                        status?.error != null -> status.error.resolve(resources)
                        !ready -> resources.getString(R.string.not_downloaded)
                        else -> null
                    }
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (voice != null && voice.id == vm.selectedId) Icon(Icons.Default.Check, resources.getString(R.string.selected),
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = {
                when { downloading -> vm.cancelOfficialDownload(); ready -> vm.editOfficial(model)
                    else -> vm.downloadOfficial(model) }
            }, enabled = downloading || (vm.connected && !vm.loading && !vm.busy && !vm.fileBusy),
                modifier = Modifier.testTag("official_${model.id}")) {
                when {
                    downloading -> Icon(Icons.Default.Close, resources.getString(R.string.cancel_download), Modifier.size(20.dp))
                    ready -> Icon(Icons.Default.Edit, resources.getString(R.string.edit_named_voice, model.displayName(resources)), Modifier.size(20.dp))
                    else -> Icon(painterResource(R.drawable.ic_download), resources.getString(R.string.download_named_model, model.displayName(resources)), Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun VoicePickerPage(vm: StudioViewModel, modifier: Modifier) {
    val resources = LocalResources.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeVoicePicker) { Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_create)) }
            Text(resources.getString(R.string.select_voice), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("voice_picker"))
        }
        vm.voices.forEach { voice ->
            val available = vm.modelFor(voice) != null
            VoiceChoiceRow(vm.voiceName(voice, resources), voice.avatarPath, if (available) "" else resources.getString(R.string.model_unavailable),
                voice.id == vm.selectedId, available && !vm.busy) { vm.chooseVoice(voice) }
        }
    }
}

@Composable
private fun VoiceChoiceRow(name: String, avatarPath: String, status: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val resources = LocalResources.current
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            VoiceAvatar(name, avatarPath)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Default.Check, resources.getString(R.string.selected), tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CheckpointImportPage(vm: StudioViewModel, draft: CheckpointDraft, pickCheckpoint: (Boolean) -> Unit, pickReference: () -> Unit,
    recordReference: () -> Unit, pickAvatar: () -> Unit, takeAvatarPhoto: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val enabled = !vm.busy && !vm.fileBusy
    val editing = draft.editingCharacterId != null
    val officialModel = vm.officialModels.firstOrNull { it.id == draft.editingCharacterId }
    val official = officialModel != null
    val displayName = officialModel?.displayName(resources) ?: draft.name
    val focus = LocalFocusManager.current
    VoiceEditorLayout(if (editing) resources.getString(R.string.edit_voice) else resources.getString(R.string.import_voice), vm::closeCheckpointImport, enabled, modifier,
        actions = {
            StudioButton(onClick = { focus.clearFocus(); vm.importCheckpoints() }, enabled = enabled && vm.connected,
                style = StudioButtonStyle.Quiet,
                modifier = Modifier.heightIn(min = 48.dp).testTag("import_checkpoints")) {
                Text(if (editing) { if (vm.importing) resources.getString(R.string.saving) else resources.getString(R.string.save) }
                    else { if (vm.importing) resources.getString(R.string.importing) else resources.getString(R.string.import_action) })
            }
        }) {
        VoiceAvatarEditor(displayName, draft.avatarPath, enabled, pickAvatar, takeAvatarPhoto)
        OutlinedTextField(displayName, vm::editCheckpointName, label = { Text(resources.getString(R.string.voice_name)) }, singleLine = true,
            enabled = enabled && !official, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("checkpoint_name"))
        listOf(true, false).forEach { gpt ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (gpt) resources.getString(R.string.gpt_weights) else resources.getString(R.string.sovits_weights), style = MaterialTheme.typography.titleMedium)
                StudioButton(onClick = { pickCheckpoint(gpt) }, enabled = enabled && !official, style = StudioButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (gpt) "pick_ckpt" else "pick_pth")) {
                    Text((if (gpt) draft.gptName else draft.sovitsName).ifBlank { if (editing) resources.getString(R.string.original_filename_unknown) else resources.getString(R.string.select_file) },
                        maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (editing && (if (gpt) draft.gpt else draft.sovits) != null) {
                    StudioButton(onClick = { vm.clearCheckpointReplacement(gpt) }, enabled = enabled,
                        style = StudioButtonStyle.Quiet) { Text(resources.getString(R.string.undo_replacement)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(resources.getString(R.string.model_version), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vm.checkpointRecognition?.resolve(resources) ?: resources.getString(R.string.awaiting_import),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("checkpoint_version"),
                color = if (vm.checkpointRecognition == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End)
        }
        ReferenceAudioInput(vm, draft.audioPath, draft.audioName, draft.durationMs,
            pickReference, recordReference, "checkpoint_reference") {
            Text(resources.getString(R.string.reference_audio), style = MaterialTheme.typography.titleMedium)
        }
        LanguageChoice(resources.getString(R.string.reference_language), draft.referenceLanguage, enabled, "checkpoint_language",
            autoText = draft.transcript, onSelect = vm::editCheckpointLanguage)
        OutlinedTextField(draft.transcript, vm::editCheckpointTranscript, label = { Text(resources.getString(R.string.reference_transcript)) },
            minLines = 3, maxLines = 6,
            enabled = enabled, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("checkpoint_reference_text"))
        if (vm.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        vm.importMessage?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("checkpoint_progress")) }
        vm.checkpointError?.let { ErrorMessage(it, vm::clearCheckpointError) }
    }
}

@Composable
private fun VoiceEditor(vm: StudioViewModel, draft: VoiceDraft, pickReference: () -> Unit, recordReference: () -> Unit,
    pickAvatar: () -> Unit, takeAvatarPhoto: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val editing = vm.voices.any { it.id == draft.id }
    var confirmDelete by remember { mutableStateOf(false) }
    VoiceEditorLayout(if (editing) resources.getString(R.string.edit_voice) else resources.getString(R.string.clone_voice), vm::closeEditor, !vm.fileBusy, modifier,
        actions = {
            StudioButton(onClick = { focus.clearFocus(); vm.saveVoice() }, enabled = !vm.fileBusy && !vm.busy,
                style = StudioButtonStyle.Quiet,
                modifier = Modifier.heightIn(min = 48.dp).testTag("save_voice")) { Text(resources.getString(R.string.save)) }
        }) {
        VoiceAvatarEditor(draft.name, draft.avatarPath, !vm.fileBusy && !vm.busy, pickAvatar, takeAvatarPhoto)
        OutlinedTextField(draft.name, { vm.edit(draft.copy(name = it)) }, label = { Text(resources.getString(R.string.voice_name)) },
            placeholder = { Text(resources.getString(R.string.voice_name_hint)) }, singleLine = true, enabled = !vm.fileBusy,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("voice_name"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepTitle("01", resources.getString(R.string.select_base_voice))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                vm.officialModels.forEach { model ->
                    val installed = vm.modelFor(model)
                    FilterChip(selected = installed != null && draft.modelDirectory == installed.relativeModelDirectory,
                        onClick = { installed?.let { vm.edit(draft.copy(modelDirectory = it.relativeModelDirectory)) } },
                        label = { Text(model.displayName(resources)) }, enabled = vm.modelFor(model) != null && !vm.fileBusy && !vm.busy)
                }
                vm.models.filterNot { vm.isOfficialDirectory(it.relativeModelDirectory) }.forEach { model ->
                    FilterChip(selected = draft.modelDirectory == model.relativeModelDirectory,
                        onClick = { vm.edit(draft.copy(modelDirectory = model.relativeModelDirectory)) },
                        label = { Text(model.displayName) }, enabled = !vm.fileBusy,
                        leadingIcon = if (draft.modelDirectory == model.relativeModelDirectory) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null)
                }
            }
        }
        ReferenceAudioInput(vm, draft.audioPath, draft.audioName, draft.durationMs,
            pickReference, recordReference, "pick_reference") {
            StepTitle("02", resources.getString(R.string.reference_audio))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepTitle("03", resources.getString(R.string.enter_transcript))
            LanguageChoice(resources.getString(R.string.reference_language), draft.referenceLanguage, !vm.fileBusy, "editor_language",
                autoText = draft.transcript, onSelect = { vm.edit(draft.copy(referenceLanguage = it)) })
            OutlinedTextField(draft.transcript, { vm.edit(draft.copy(transcript = it)) },
                minLines = 3, maxLines = 6,
                enabled = !vm.fileBusy, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("reference_text"))
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
private fun SettingsPage(vm: StudioViewModel, openAbout: () -> Unit, openMoreModels: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    var advanced by rememberSaveable { mutableStateOf(false) }
    val advancedRotation by animateFloatAsState(if (advanced) 90f else 0f, tween(180), label = "advanced_settings")
    Column(modifier.fillMaxHeight()) {
        Text(resources.getString(R.string.settings), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)) {
            Column(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(resources.getString(R.string.appearance), style = MaterialTheme.typography.titleMedium)
                val modes = listOf(ThemeMode.SYSTEM to resources.getString(R.string.theme_system), ThemeMode.LIGHT to resources.getString(R.string.theme_light), ThemeMode.DARK to resources.getString(R.string.theme_dark))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = vm.theme == value, onClick = { vm.changeTheme(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size, RoundedCornerShape(8.dp)),
                            icon = {}, modifier = Modifier.testTag("theme_${value.name}")) { Text(label) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(resources.getString(R.string.gpu_acceleration), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.gpuEnabled && vm.gpuAvailable, onCheckedChange = vm::setGpu, enabled = vm.gpuAvailable && !vm.busy)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(resources.getString(R.string.device_acceleration), style = MaterialTheme.typography.titleMedium)
                    Text(vm.acceleration.device.ifBlank { resources.getString(R.string.device_loading) }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.acceleration.detail.resolve(resources), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("device_acceleration_status"))
                }
                Switch(checked = vm.deviceAccelerationEnabled && vm.acceleration.available && vm.gpuEnabled,
                    onCheckedChange = vm::setDeviceAcceleration,
                    enabled = vm.acceleration.available && vm.gpuEnabled && !vm.busy,
                    modifier = Modifier.testTag("device_acceleration"))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable(onClick = openMoreModels).heightIn(min = 56.dp).testTag("more_models"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(resources.getString(R.string.more_models), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 56.dp).testTag("advanced_settings"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(resources.getString(R.string.advanced_settings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (advanced) resources.getString(R.string.collapse) else resources.getString(R.string.expand),
                    Modifier.size(22.dp).rotate(advancedRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(advanced, enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(resources.getString(R.string.local_api), modifier = Modifier.weight(1f))
                        Switch(checked = vm.apiEnabled, onCheckedChange = vm::setApi)
                    }
                    if (vm.apiEnabled) {
                        Text(resources.getString(R.string.local_api_description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(vm.apiPort, vm::editPort, label = { Text(resources.getString(R.string.port)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = vm.apiPortError != null,
                            trailingIcon = {
                                IconButton(onClick = vm::applyPort) {
                                    Icon(Icons.Default.Check, resources.getString(R.string.save_port), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            supportingText = vm.apiPortError?.let { error -> ({
                                DismissErrorAfterTimeout(error, vm::clearPortError)
                                Text(error.resolve(resources))
                            }) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            StarInvitation(onClick = {
                runCatching { uriHandler.openUri("https://github.com/Renakoni/Voicebox") }
                    .onFailure { vm.notify(UiText(R.string.github_open_failed)) }
            })
            Row(Modifier.fillMaxWidth().clickable(onClick = openAbout).heightIn(min = 56.dp).testTag("open_about"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(resources.getString(R.string.about), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepTitle(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun ErrorMessage(message: UiText, onDismiss: () -> Unit) {
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
private fun DismissErrorAfterTimeout(message: UiText, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 10_000L, containsText = true,
    ) ?: 10_000L
    LaunchedEffect(message, timeout) {
        delay(timeout)
        dismiss()
    }
}
