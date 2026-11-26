@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.renakoni.voicebox.studio.creation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.QwenModelPackage
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.studio.*

private val ComposerShape = RoundedCornerShape(StudioCardRadius)

@Composable
internal fun CreatePage(vm: StudioViewModel, save: (StudioOutput) -> Unit, share: (StudioOutput) -> Unit,
    sendQq: (StudioOutput) -> Unit, pickRuntime: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    if (vm.selectedVoice?.let(vm::modelFor) == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (!vm.loading) {
                if (vm.needsModel) EmptyStudio(R.string.install_first_model, R.string.go_models) { vm.tab = StudioTab.MODELS }
                else EmptyStudio(R.string.first_voice, R.string.go_voices) { vm.tab = StudioTab.VOICES }
            }
        }
        return
    }
    val settings = vm.creationSettings
    Column(modifier.fillMaxHeight()) {
        PageHeader(resources.getString(R.string.create_title))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) createContent@ {
            if (vm.loading) {
                StudioCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(resources.getString(R.string.voices_loading), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RoundedProgress(null)
                    }
                }
            } else if (!vm.runtimeReady) {
                StudioCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(resources.getString(R.string.engine_setup), style = MaterialTheme.typography.titleMedium)
                        val qwen = vm.selectedVoice?.model?.backendId == QwenModelPackage.BACKEND
                        val hint = if (qwen) resources.getString(R.string.install_missing_model_parts, resources.getString(R.string.qwen_audio_codec))
                            else vm.resourceHint?.let { UiText(it).resolve(resources) } ?: resources.getString(R.string.voice_resources_hint)
                        Text(hint, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (qwen) StudioButton(onClick = { vm.tab = StudioTab.MODELS }, style = StudioButtonStyle.Secondary) {
                            Text(resources.getString(R.string.go_models))
                        } else if (vm.canImportArchive) StudioButton(onClick = pickRuntime, enabled = vm.connected && !vm.busy,
                            style = StudioButtonStyle.Secondary, modifier = Modifier.padding(top = 4.dp)) {
                            Text(resources.getString(R.string.import_resources))
                        }
                        vm.importMessage?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            VoiceCard(vm, settings)
            Composer(vm, settings)
            settings.model.Panels(settings, enabled = !vm.busy)
            if (!vm.busy && vm.state.stage == GenerationStage.CANCELLED) {
                Text(resources.getString(R.string.generation_stopped), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.generationError?.let { ErrorMessage(it, vm::clearGenerationError) }
            if (vm.streamPreview != null) return@createContent
            LatestTake(vm, save, share, sendQq)
        }
        if (vm.streamPreview == null) GenerateDock(vm, settings)
    }
}

// ---------------------------------------------------------------------------------------------
// Voice
// ---------------------------------------------------------------------------------------------

@Composable
private fun VoiceCard(vm: StudioViewModel, settings: CreationSettings) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val voice = vm.selectedVoice
    StudioCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !vm.busy, onClickLabel = resources.getString(R.string.change_voice),
            onClick = vm::openVoicePicker).heightIn(min = 72.dp).padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (voice != null) {
                val presetActive = settings.preset != null
                val title = vm.modelFor(voice)?.displayName?.takeIf { presetActive } ?: vm.voiceName(voice, resources)
                val subtitle = vm.variantLabel(voice.model).resolve(resources).takeIf { it.isNotBlank() && it != title }
                VoiceAvatar(title, voice.avatarPath.takeUnless { presetActive }.orEmpty(), Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceContainerHigh))
                Text(resources.getString(R.string.select_voice), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant)
            }
            Text(resources.getString(R.string.change_voice_short), style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        val extras = settings.presets.isNotEmpty() || vm.auxiliaryLimit > 0
        if (extras) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant.copy(alpha = .6f))
        settings.model.VoiceExtras(settings, enabled = !vm.busy)
        if (vm.auxiliaryLimit > 0) AuxiliaryReferences(vm)
    }
}

@Composable
private fun AuxiliaryReferences(vm: StudioViewModel) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf(false) }
    val enabled = !vm.busy && vm.auxiliaryChoices.isNotEmpty()
    Box {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { choosing = true }
            .heightIn(min = 52.dp).padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(resources.getString(R.string.aux_references_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
            Text("${vm.auxiliaryPaths.size} / ${vm.auxiliaryLimit}", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = if (vm.auxiliaryPaths.isNotEmpty()) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = resources.getString(R.string.aux_references, vm.auxiliaryPaths.size, vm.auxiliaryLimit) })
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(choosing, onDismissRequest = { choosing = false }, shape = RoundedCornerShape(14.dp)) {
            vm.auxiliaryChoices.forEach { voice ->
                val checked = voice.audioPath in vm.auxiliaryPaths
                DropdownMenuItem(text = { Text(vm.voiceName(voice, resources)) },
                    leadingIcon = { Checkbox(checked, onCheckedChange = null) },
                    enabled = checked || vm.auxiliaryPaths.size < vm.auxiliaryLimit,
                    onClick = { vm.toggleAuxiliary(voice.audioPath) })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Writing surface
// ---------------------------------------------------------------------------------------------

@Composable
private fun Composer(vm: StudioViewModel, settings: CreationSettings) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(if (focused) colors.primary.copy(alpha = .55f) else colors.outlineVariant.copy(alpha = .45f),
        tween(180), label = "composer edge")
    val writingStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp)
    Column(Modifier.fillMaxWidth().clip(ComposerShape).background(colors.surfaceContainer)
        .border(1.dp, edge, ComposerShape)) {
        BasicTextField(value = vm.text, onValueChange = vm::updateText, enabled = !vm.busy,
            textStyle = writingStyle.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary), minLines = 5, maxLines = 12,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)
                .semantics { contentDescription = resources.getString(R.string.speech_text) },
            decorationBox = { inner ->
                Box {
                    if (vm.text.isEmpty()) Text(resources.getString(R.string.speech_text_hint), style = writingStyle,
                        color = colors.onSurfaceVariant.copy(alpha = .8f))
                    inner()
                }
            })
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(resources.getString(R.string.character_count, vm.text.length), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
            if (vm.text.isNotEmpty()) IconButton(onClick = { vm.updateText("") }, enabled = !vm.busy) {
                Icon(Icons.Default.Close, resources.getString(R.string.clear_text), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
            StudioButton(onClick = { vm.updateText(resources.getString(when (settings.sampleLanguage) {
                "ja" -> R.string.speech_sample_ja
                "en" -> R.string.speech_sample_en
                else -> R.string.speech_sample_zh
            })) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet) {
                Text(resources.getString(R.string.sample_text))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Generate dock
// ---------------------------------------------------------------------------------------------

@Composable
private fun GenerateDock(vm: StudioViewModel, settings: CreationSettings) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().background(colors.background)) {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .45f))
        AnimatedContent(vm.busy, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "generate dock") { busy ->
            if (busy) {
                val progress = generationStatus(vm.state, resources)
                val title = when {
                    vm.importing -> resources.getString(R.string.resources_importing)
                    vm.cancelling -> resources.getString(R.string.stopping)
                    else -> progress.title
                }
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!vm.importing && !vm.cancelling) progress.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!vm.importing) StudioButton(onClick = vm::cancel, enabled = !vm.cancelling,
                            style = StudioButtonStyle.Secondary) { Text(resources.getString(R.string.stop)) }
                    }
                    RoundedProgress(progress.percent?.takeIf { !vm.importing && !vm.cancelling }, Modifier.padding(end = 8.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    settings.model.Toolbar(settings, enabled = !vm.busy)
                    StudioButton(onClick = { focus.clearFocus(); vm.generate() }, enabled = vm.canGenerate,
                        modifier = Modifier.weight(1f).height(48.dp)) {
                        val label = when {
                            vm.selectedRuntimeUnsupported -> R.string.model_gpu_runtime_unavailable
                            settings.streaming -> R.string.ming_generate_and_play
                            else -> R.string.generate_speech
                        }
                        Text(resources.getString(label))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundedProgress(percent: Int?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val track = colors.outlineVariant.copy(alpha = .6f)
    val shaped = modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
    if (percent == null) LinearProgressIndicator(shaped, trackColor = track, gapSize = 0.dp)
    else LinearProgressIndicator(progress = { percent / 100f }, modifier = shaped, trackColor = track,
        gapSize = 0.dp, drawStopIndicator = {})
}

// ---------------------------------------------------------------------------------------------
// Latest take
// ---------------------------------------------------------------------------------------------

@Composable
private fun LatestTake(vm: StudioViewModel, save: (StudioOutput) -> Unit, share: (StudioOutput) -> Unit,
    sendQq: (StudioOutput) -> Unit) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val output = vm.output
    Column {
        SectionLabel(resources.getString(R.string.latest_audio), Modifier.padding(top = 8.dp))
        if (output == null) {
            Column(Modifier.fillMaxWidth().clip(ComposerShape)
                .border(1.dp, colors.outlineVariant.copy(alpha = .6f), ComposerShape).padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietWave()
                Text(resources.getString(R.string.no_generated_audio), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
            return@Column
        }
        StudioCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(output.voiceName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (output.text.isNotBlank() && output.text.trim() != vm.text.trim()) StatusTag(resources.getString(R.string.text_changed))
                    Spacer(Modifier.weight(1f))
                    vm.state.inferenceRtf?.takeIf { vm.state.latestOutputFilePath == output.path }?.let { rtf ->
                        Text(resources.getString(R.string.inference_rtf, rtf),
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = colors.onSurfaceVariant)
                    }
                }
                if (output.text.isNotBlank()) Row(Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().clip(CircleShape).background(colors.primary.copy(alpha = .5f)))
                    Text(output.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                OutputPlayback(vm.preview.takeIf { it.path == output.path } ?: PreviewState(), output.durationMs, !vm.busy,
                    onToggle = { vm.audio.toggle(output.path) }, onSeek = vm.audio::seek)
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                TakeAction(resources.getString(R.string.save_audio), Modifier.weight(1f), true, { save(output) }) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                }
                TakeAction(resources.getString(R.string.share), Modifier.weight(1f), true, { share(output) }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                }
                TakeAction(resources.getString(R.string.send_qq), Modifier.weight(1f), !vm.busy, { sendQq(output) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun TakeAction(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f)
    Row(modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            icon()
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuietWave() {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
    Canvas(Modifier.size(width = 56.dp, height = 24.dp)) {
        val bars = listOf(.25f, .5f, .8f, .45f, 1f, .6f, .35f, .7f, .3f)
        val step = size.width / (bars.size + 1)
        bars.forEachIndexed { index, height ->
            val x = step * (index + 1)
            drawLine(color, Offset(x, size.height * (1 - height) / 2), Offset(x, size.height * (1 + height) / 2),
                strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun EmptyStudio(message: Int, action: Int, onStart: () -> Unit) {
    val resources = LocalResources.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        QuietWave()
        Text(resources.getString(message), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        StudioButton(onClick = onStart) { Text(resources.getString(action)) }
    }
}
