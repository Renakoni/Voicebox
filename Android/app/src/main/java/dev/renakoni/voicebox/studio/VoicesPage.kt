package dev.renakoni.voicebox.studio

import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.MingModelPackage
import dev.renakoni.voicebox.QwenModelPackage
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.OfficialVoice
import dev.renakoni.voicebox.bridge.ResourcePack
import dev.renakoni.voicebox.core.OfficialDownloadState
import dev.renakoni.voicebox.studio.creation.EmptyStudio
import dev.renakoni.voicebox.studio.creation.ming.MingPresetBrowser

private val previewOfficialVoices = listOf(
    OfficialVoice(ModelRef("gptsovits", "v2ProPlus", "official-v2proplus"), "V2 ProPlus", "model_v2_pro_plus"),
    OfficialVoice(ModelRef("gptsovits", "v5turbo", "official-v5turbo"), "V5 Turbo", "model_v5_turbo"),
    OfficialVoice(ModelRef("qwen3", "Q4_K_M", "qwen3-tts-0.6b-base"), "Qwen3-TTS-0.6B Q4", ""),
)

@Composable
internal fun VoicesPage(vm: StudioViewModel, modifier: Modifier) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    var officialExpanded by rememberSaveable { mutableStateOf(true) }
    var browsingMing by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxHeight()) {
        if (vm.needsModel && !vm.modelPreview) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                if (!vm.loading) EmptyStudio(R.string.install_first_model, R.string.go_models) { vm.tab = StudioTab.MODELS }
            }
            return@Column
        }
        PageHeader(resources.getString(R.string.my_voices))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioButton(onClick = { vm.beginVoice() }, enabled = vm.cloneModels.isNotEmpty() && !vm.loading && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Primary, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text(resources.getString(R.string.clone_voice))
            }
            val formats = vm.checkpointFormats
            if (vm.modelPreview || formats.isNotEmpty()) Box(Modifier.weight(1f).fillMaxHeight()) {
                var choosingFormat by remember { mutableStateOf(false) }
                StudioButton(onClick = {
                    if (formats.size == 1) vm.beginCheckpointImport(formats.single()) else choosingFormat = true
                }, enabled = formats.isNotEmpty() && vm.connected && !vm.busy && !vm.fileBusy,
                    style = StudioButtonStyle.Secondary, modifier = Modifier.fillMaxSize().heightIn(min = 44.dp)) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(17.dp))
                    Text(resources.getString(R.string.import_voice))
                }
                DropdownMenu(choosingFormat, onDismissRequest = { choosingFormat = false }) {
                    formats.forEach { format ->
                        DropdownMenuItem(text = { Text(format.title) }, onClick = {
                            choosingFormat = false
                            vm.beginCheckpointImport(format)
                        })
                    }
                }
            }
        }
        val officialModels = if (vm.modelPreview) previewOfficialVoices else vm.officialModels
        val mingWeights = vm.mingVoiceWeights
        val mingVoice = vm.voices.firstOrNull {
            it.builtIn && it.model == MingModelPackage.modelRef && vm.creationSettings.presetsFor(it.model).isNotEmpty()
        }
        val officialCount = officialModels.size + if (mingWeights == null) 0 else 1
        if (mingVoice != null && browsingMing && !vm.busy && !vm.fileBusy) {
            MingPresetBrowser(vm.creationSettings.presetsFor(mingVoice.model),
                vm.creationSettings.preset.takeIf { vm.selectedId == mingVoice.id }, onBack = { browsingMing = false }) { id ->
                browsingMing = false
                vm.select(mingVoice)
                vm.creationSettings.changePreset(id)
                vm.tab = StudioTab.CREATE
            }
        }
        val otherVoices = vm.voices.filterNot { voice -> voice.builtIn && vm.isOfficial(voice.model) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            if (officialCount > 0) item(key = "label:official") {
                val state = resources.getString(if (officialExpanded) R.string.expanded else R.string.collapsed)
                val rotation by animateFloatAsState(if (officialExpanded) 180f else 0f, tween(220), label = "official voices")
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { officialExpanded = !officialExpanded }
                    .semantics { stateDescription = state }.heightIn(min = 48.dp).padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(resources.getString(R.string.voices_official), Modifier.weight(1f))
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(rotation), tint = colors.onSurfaceVariant)
                }
            }
            if (officialExpanded) {
                itemsIndexed(officialModels, key = { _, official -> "official:" + official.ref }) { index, official ->
                    OfficialVoiceRow(vm, official, groupedShape(index, officialCount))
                }
                if (mingWeights != null) item(key = "official:ming") {
                    MingOfficialVoiceRow(vm, mingWeights, mingVoice, groupedShape(officialCount - 1, officialCount)) {
                        browsingMing = true
                    }
                }
            }
            if (otherVoices.isNotEmpty()) item(key = "label:custom") {
                SectionLabel(resources.getString(R.string.voices_custom),
                    Modifier.padding(top = if (officialCount > 0 && officialExpanded) 14.dp else 0.dp))
            }
            itemsIndexed(otherVoices, key = { _, voice -> voice.id }) { index, voice ->
                val available = vm.modelFor(voice) != null
                val selected = vm.selectedId == voice.id
                VoiceTile(groupedShape(index, otherVoices.size), selected, enabled = !vm.busy && !vm.fileBusy && available,
                    onClick = { vm.select(voice); vm.tab = StudioTab.CREATE },
                    avatar = { RingedAvatar(voice.name, voice.avatarPath, selected, dim = !available) },
                    trailing = {
                        if (!voice.builtIn || vm.acceptsReference(voice.model)) IconButton(onClick = { vm.beginEditVoice(voice) }, enabled = !vm.busy && !vm.fileBusy) {
                            Icon(Icons.Outlined.Edit, resources.getString(R.string.edit_named_voice, voice.name),
                                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }) {
                    Text(voice.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    val detail = listOfNotNull(vm.variantLabel(voice.model).resolve(resources),
                        when {
                            !available -> resources.getString(R.string.model_unavailable)
                            voice.model.backendId == QwenModelPackage.BACKEND && !vm.resourcesReady(voice.model) ->
                                resources.getString(R.string.install_missing_model_parts, resources.getString(R.string.qwen_audio_codec))
                            else -> null
                        }).joinToString(" · ")
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected) Text(resources.getString(R.string.selected), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun MingOfficialVoiceRow(vm: StudioViewModel, pack: ResourcePack, voice: StudioVoice?, shape: Shape,
    browse: () -> Unit) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val title = vm.mingName
    val weightTitle = resources.getString(R.string.ming_official_weights)
    val status = vm.officialDownload.takeIf { it.voice == MingModelPackage.modelRef }
    val downloading = status?.running == true
    val enabled = !vm.modelPreview && vm.connected && !vm.loading && !vm.busy && !vm.fileBusy
    val installed = pack.installed || vm.modelPreview
    val selected = voice != null && vm.selectedId == voice.id && vm.creationSettings.preset != null
    VoiceTile(shape, selected, enabled = enabled && installed,
        onClick = { if (voice == null) vm.tab = StudioTab.MODELS else browse() },
        avatar = { RingedAvatar(title, voice?.avatarPath.orEmpty(), selected, dim = !installed) },
        trailing = {
            OfficialWeightAction(weightTitle, installed, enabled, status, vm::downloadMingVoices, vm::cancelOfficialDownload) {
                vm.removeModelPart(MingModelPackage.BACKEND, MingModelPackage.OFFICIAL)
            }
        }) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (installed) colors.onSurface else colors.onSurfaceVariant)
        val error = status?.error
        val detail = when {
            downloading -> weightTitle + (status.percent?.let { " · $it%" } ?: "")
            !installed && error != null -> error.resolve(resources)
            !installed -> resources.getString(R.string.ming_official_weights_download,
                Formatter.formatShortFileSize(LocalContext.current, pack.downloadBytes))
            voice == null && !vm.modelPreview -> resources.getString(R.string.shared_resources_missing)
            selected -> resources.getString(R.string.selected)
            else -> null
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = if (!installed && error != null) colors.error else colors.onSurfaceVariant)
    }
}

/** One row of a grouped voice list: tonal tile, avatar, text block, a trailing action. */
@Composable
internal fun VoiceTile(shape: Shape, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    avatar: @Composable () -> Unit, trailing: @Composable () -> Unit,
    onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) colors.primary.copy(alpha = .10f).compositeOver(colors.surfaceContainer) else colors.surfaceContainer,
        tween(200), label = "voice tile")
    val action = if (onLongClick == null) Modifier.clickable(enabled = enabled, onClick = onClick)
        else Modifier.combinedClickable(enabled = enabled, onClick = onClick,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(shape).background(background),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(action).heightIn(min = 76.dp)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            avatar()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
        trailing()
        Spacer(Modifier.width(6.dp))
    }
}

/** Avatar with a thin clay ring when it is the voice in use. */
@Composable
internal fun RingedAvatar(name: String, path: String, selected: Boolean, dim: Boolean = false) {
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(200), label = "avatar ring")
    Box(Modifier.size(50.dp).border(1.5.dp, ring, CircleShape).padding(4.dp)) {
        VoiceAvatar(name, path, Modifier.fillMaxSize().alpha(if (dim) .5f else 1f))
    }
}

@Composable
private fun OfficialVoiceRow(vm: StudioViewModel, model: OfficialVoice, shape: Shape) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val qwen = model.ref.backendId == QwenModelPackage.BACKEND
    val weightPack = if (qwen) vm.qwenVoiceWeights else null
    val installed = (weightPack?.installed ?: (vm.modelFor(model) != null)) || vm.modelPreview
    val ready = vm.resourcesReady(model.ref) || vm.modelPreview
    val voice = vm.voiceFor(model)
    val status = vm.officialDownload.takeIf { it.voice == model.ref }
    val error = status?.error
    val downloading = status?.running == true
    val selected = voice != null && voice.id == vm.selectedId
    val name = model.displayName(resources)
    val enabled = !vm.modelPreview && vm.connected && !vm.loading && !vm.busy && !vm.fileBusy
    VoiceTile(shape, selected, enabled = installed && enabled,
        onClick = {
            if (qwen && !ready) vm.tab = StudioTab.MODELS
            else if (voice != null) { vm.select(voice); vm.tab = StudioTab.CREATE }
            else vm.editOfficial(model)
        },
        onLongClick = if (qwen && !ready) null else ({ vm.editOfficial(model) }),
        onLongClickLabel = resources.getString(R.string.edit_named_voice, name),
        avatar = { RingedAvatar(name, voice?.avatarPath.orEmpty(), selected, dim = !installed) },
        trailing = {
            OfficialWeightAction(name, installed, enabled, status, { vm.downloadOfficial(model) }, vm::cancelOfficialDownload) {
                vm.removeOfficialWeights(model)
            }
        }) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (installed) colors.onSurface else colors.onSurfaceVariant)
        val detail = when {
            downloading -> status.label?.resolve(resources).orEmpty() + (status.percent?.let { " $it%" } ?: "")
            !ready && error != null -> error.resolve(resources)
            !installed && weightPack != null -> resources.getString(R.string.ming_official_weights_download,
                Formatter.formatShortFileSize(LocalContext.current, weightPack.remainingBytes))
            !installed -> resources.getString(R.string.not_downloaded)
            !ready && qwen -> resources.getString(R.string.install_missing_model_parts, resources.getString(R.string.qwen_audio_codec))
            !ready -> resources.getString(R.string.shared_resources_missing)
            else -> null
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = if (!downloading && !ready && error != null) colors.error else colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (selected) Text(resources.getString(R.string.selected), style = MaterialTheme.typography.labelMedium,
            color = colors.primary)
    }
}

@Composable
private fun OfficialWeightAction(name: String, installed: Boolean, enabled: Boolean, status: OfficialDownloadState?,
    download: () -> Unit, cancel: () -> Unit, remove: () -> Unit) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val downloading = status?.running == true
    var removing by remember { mutableStateOf(false) }
    when {
        downloading -> IconButton(onClick = cancel) {
            Box(contentAlignment = Alignment.Center) {
                val percent = status.percent
                if (percent == null) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp,
                    trackColor = colors.outlineVariant)
                else CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(30.dp),
                    strokeWidth = 2.dp, trackColor = colors.outlineVariant, gapSize = 0.dp)
                Icon(Icons.Default.Close, resources.getString(R.string.cancel_download), Modifier.size(14.dp))
            }
        }
        installed -> IconButton(onClick = { removing = true }, enabled = enabled) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.error.copy(alpha = .14f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, resources.getString(R.string.model_remove_part, name),
                    Modifier.size(18.dp), tint = colors.error)
            }
        }
        else -> IconButton(onClick = download, enabled = enabled) {
            Box(Modifier.size(32.dp).border(1.dp, colors.outline.copy(alpha = .55f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_download), resources.getString(R.string.download_named_model, name),
                    Modifier.size(16.dp), tint = colors.onSurface)
            }
        }
    }
    if (removing) RemoveModelPartDialog(name, enabled, onDismiss = { removing = false }, onConfirm = {
        removing = false
        remove()
    })
}
