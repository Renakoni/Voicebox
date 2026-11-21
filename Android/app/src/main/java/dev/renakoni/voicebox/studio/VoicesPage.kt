package dev.renakoni.voicebox.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.OfficialVoice

@Composable
internal fun VoicesPage(vm: StudioViewModel, modifier: Modifier) {
    val resources = LocalResources.current
    Column(modifier.fillMaxHeight()) {
        PageHeader(resources.getString(R.string.my_voices))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioButton(onClick = { vm.beginVoice() }, enabled = !vm.loading && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Primary, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text(resources.getString(R.string.clone_voice))
            }
            if (vm.canImportCheckpoints) StudioButton(onClick = vm::beginCheckpointImport, enabled = vm.connected && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Secondary, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp)) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(17.dp))
                Text(resources.getString(R.string.import_voice))
            }
        }
        val officialModels = vm.officialModels
        val otherVoices = vm.voices.filterNot { voice -> voice.builtIn && vm.isOfficial(voice.model) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            if (officialModels.isNotEmpty()) item(key = "label:official") {
                SectionLabel(resources.getString(R.string.voices_official))
            }
            itemsIndexed(officialModels, key = { _, official -> "official:" + official.ref }) { index, official ->
                OfficialVoiceRow(vm, official, groupedShape(index, officialModels.size))
            }
            if (otherVoices.isNotEmpty()) item(key = "label:custom") {
                SectionLabel(resources.getString(R.string.voices_custom),
                    Modifier.padding(top = if (officialModels.isNotEmpty()) 14.dp else 0.dp))
            }
            itemsIndexed(otherVoices, key = { _, voice -> voice.id }) { index, voice ->
                val available = vm.modelFor(voice) != null
                val selected = vm.selectedId == voice.id
                VoiceTile(groupedShape(index, otherVoices.size), selected, enabled = !vm.busy && available,
                    onClick = { vm.select(voice); vm.tab = StudioTab.CREATE },
                    avatar = { RingedAvatar(voice.name, voice.avatarPath, selected, dim = !available) },
                    trailing = {
                        IconButton(onClick = { vm.beginEditVoice(voice) }, enabled = !vm.busy && !vm.fileBusy) {
                            Icon(Icons.Outlined.Edit, resources.getString(R.string.edit_named_voice, voice.name),
                                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }) {
                    Text(voice.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    val detail = listOfNotNull(vm.variantLabel(voice.model).resolve(resources),
                        if (!available) resources.getString(R.string.model_unavailable) else null).joinToString(" · ")
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected) Text(resources.getString(R.string.selected), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** One row of a grouped voice list: tonal tile, avatar, text block, a trailing action. */
@Composable
internal fun VoiceTile(shape: Shape, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    avatar: @Composable () -> Unit, trailing: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) colors.primary.copy(alpha = .10f).compositeOver(colors.surfaceContainer) else colors.surfaceContainer,
        tween(200), label = "voice tile")
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(shape).background(background),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClick).heightIn(min = 76.dp)
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
    val installed = vm.modelFor(model) != null
    val ready = vm.resourcesReady(model.ref)
    val voice = vm.voiceFor(model)
    val status = vm.officialDownload.takeIf { it.voice == model.ref }
    val error = status?.error
    val downloading = status?.running == true
    val selected = voice != null && voice.id == vm.selectedId
    val name = model.displayName(resources)
    VoiceTile(shape, selected, enabled = installed && !vm.busy && !vm.fileBusy,
        onClick = { if (voice != null) { vm.select(voice); vm.tab = StudioTab.CREATE } else vm.editOfficial(model) },
        avatar = { RingedAvatar(name, voice?.avatarPath.orEmpty(), selected, dim = !installed) },
        trailing = {
            IconButton(onClick = {
                when { downloading -> vm.cancelOfficialDownload(); ready -> vm.editOfficial(model)
                    else -> vm.downloadOfficial(model) }
            }, enabled = downloading || (vm.connected && !vm.loading && !vm.busy && !vm.fileBusy)) {
                when {
                    downloading -> Box(contentAlignment = Alignment.Center) {
                        val percent = status?.percent
                        if (percent == null) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp,
                            trackColor = colors.outlineVariant)
                        else CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(30.dp),
                            strokeWidth = 2.dp, trackColor = colors.outlineVariant, gapSize = 0.dp)
                        Icon(Icons.Default.Close, resources.getString(R.string.cancel_download), Modifier.size(14.dp))
                    }
                    ready -> Icon(Icons.Outlined.Edit, resources.getString(R.string.edit_named_voice, name), Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant)
                    else -> Box(Modifier.size(32.dp).border(1.dp, colors.outline.copy(alpha = .55f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_download), resources.getString(R.string.download_named_model, name),
                            Modifier.size(16.dp), tint = colors.onSurface)
                    }
                }
            }
        }) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (installed) colors.onSurface else colors.onSurfaceVariant)
        val detail = when {
            downloading -> status?.label?.resolve(resources).orEmpty() + (status?.percent?.let { " $it%" } ?: "")
            !ready && error != null -> error.resolve(resources)
            !installed -> resources.getString(R.string.not_downloaded)
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
