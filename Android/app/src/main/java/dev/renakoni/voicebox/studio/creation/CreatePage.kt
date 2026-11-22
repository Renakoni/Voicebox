@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.renakoni.voicebox.studio.creation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.studio.*

@Composable
internal fun CreatePage(vm: StudioViewModel, save: (StudioOutput) -> Unit, share: (StudioOutput) -> Unit,
    sendQq: (StudioOutput) -> Unit, pickRuntime: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    if (vm.voices.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (!vm.loading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(resources.getString(R.string.first_voice), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    StudioButton(onClick = { vm.tab = StudioTab.VOICES }) { Text(resources.getString(R.string.go_voices)) }
                }
            }
        }
        return
    }
    val focus = LocalFocusManager.current
    val settings = vm.creationSettings
    Column(modifier.fillMaxHeight()) {
        PageHeader(resources.getString(R.string.create_title))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp)) createContent@ {
            if (vm.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(resources.getString(R.string.voices_loading), modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!vm.runtimeReady) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(resources.getString(R.string.engine_setup), style = MaterialTheme.typography.titleMedium)
                    Text(vm.resourceHint?.let { UiText(it).resolve(resources) }
                        ?: resources.getString(R.string.voice_resources_hint), style = MaterialTheme.typography.bodyMedium)
                    if (vm.canImportArchive) StudioButton(onClick = pickRuntime, enabled = vm.connected && !vm.busy) {
                        Text(resources.getString(R.string.import_resources))
                    }
                    vm.importMessage?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val voice = vm.selectedVoice
                Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !vm.busy, onClick = vm::openVoicePicker)
                    .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (voice != null) {
                        val presetActive = settings.preset != null
                        val title = vm.modelFor(voice)?.displayName?.takeIf { presetActive } ?: vm.voiceName(voice, resources)
                        VoiceAvatar(title, voice.avatarPath.takeUnless { presetActive }.orEmpty(), Modifier.size(36.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    } else Text(resources.getString(R.string.select_voice), modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ArrowDropDown, resources.getString(R.string.change_voice), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                settings.model.Toolbar(settings, enabled = !vm.busy)
            }
            if (vm.auxiliaryLimit > 0) {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    StudioButton(onClick = { choosing = true }, enabled = !vm.busy && vm.auxiliaryChoices.isNotEmpty(),
                        style = StudioButtonStyle.Quiet) {
                        Text(resources.getString(R.string.aux_references, vm.auxiliaryPaths.size, vm.auxiliaryLimit))
                    }
                    DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val writingStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp)
            BasicTextField(value = vm.text, onValueChange = vm::updateText, enabled = !vm.busy,
                textStyle = writingStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), minLines = 4, maxLines = 10,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp)
                    .semantics { contentDescription = resources.getString(R.string.speech_text) },
                decorationBox = { inner ->
                    Box {
                        if (vm.text.isEmpty()) Text(resources.getString(R.string.speech_text_hint), style = writingStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                })
            settings.model.Panels(settings, enabled = !vm.busy)
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Text(resources.getString(R.string.character_count, vm.text.length), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StudioButton(onClick = { vm.updateText(resources.getString(when (settings.sampleLanguage) {
                    "ja" -> R.string.speech_sample_ja
                    "en" -> R.string.speech_sample_en
                    else -> R.string.speech_sample_zh
                })) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet) {
                    Text(resources.getString(R.string.sample_text))
                }
                StudioButton(onClick = { focus.clearFocus(); vm.generate() }, enabled = vm.canGenerate) {
                    Text(resources.getString(if (settings.streaming) R.string.ming_generate_and_play else R.string.generate_speech))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            if (vm.busy && vm.streamPreview == null) {
                val progress = generationStatus(vm.state, resources)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (vm.importing) resources.getString(R.string.resources_importing) else if (vm.cancelling) resources.getString(R.string.stopping) else progress.title,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!vm.importing) StudioButton(onClick = vm::cancel, enabled = !vm.cancelling,
                            style = StudioButtonStyle.Quiet) { Text(resources.getString(R.string.stop)) }
                    }
                    if (!vm.importing && !vm.cancelling) progress.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (progress.percent != null && !vm.importing && !vm.cancelling) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (!vm.busy && vm.state.stage == GenerationStage.CANCELLED) {
                Text(resources.getString(R.string.generation_stopped), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.generationError?.let { ErrorMessage(it, vm::clearGenerationError) }
            if (vm.streamPreview != null) return@createContent
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
                vm.state.inferenceRtf?.takeIf { vm.state.latestOutputFilePath == output.path }?.let { rtf ->
                    Text(resources.getString(R.string.inference_rtf, rtf), modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(resources.getString(R.string.latest_audio), style = MaterialTheme.typography.titleMedium)
                Text(resources.getString(R.string.no_generated_audio), modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            StudioButton(onClick = { sendQq(output) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet) {
                Text(resources.getString(R.string.send_qq))
            }
        }
    }
}
