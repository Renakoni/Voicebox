package dev.renakoni.voicebox.studio

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.renakoni.voicebox.MingModelPackage
import dev.renakoni.voicebox.QwenModelPackage
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.VoiceBoxBackendService
import dev.renakoni.voicebox.core.BridgeInstallState
import dev.renakoni.voicebox.core.BridgePackage
import dev.renakoni.voicebox.core.i18n.UiText

private val Tabular = "tnum"
private val PartMarkSize = 28.dp
private val PartActionWidth = 40.dp

@Composable
internal fun ModelsPage(vm: StudioViewModel, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        PageHeader(stringResource(R.string.models))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.bridgeStates == null && !vm.loading && vm.connected) {
                StudioButton(vm::refreshResources, enabled = !vm.busy && !vm.fileBusy, style = StudioButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.model_reload))
                }
            }
            vm.backendPackages.forEach { archive -> ModelFamily(vm, archive) }
        }
    }
}

@Composable
private fun ModelFamily(vm: StudioViewModel, archive: BridgePackage) {
    val spec = archive.spec
    val name = spec.displayName
    val colors = MaterialTheme.colorScheme
    val states = vm.bridgeStates
    val resources = LocalResources.current
    val context = LocalContext.current
    val bridgeState = states?.get(spec.id)
    val bridgeReady = bridgeState == BridgeInstallState.CURRENT
    val ming = spec.id == MingModelPackage.BACKEND
    val qwen = spec.id == QwenModelPackage.BACKEND
    val rechecking = vm.recheckingModel == spec.id
    val unsupported = states != null && !vm.modelPreview && !archive.supported
    val storedBridge = bridgeReady || bridgeState == BridgeInstallState.INCOMPATIBLE
    val packs = (vm.resourcePacks[spec.id] ?: spec.initialResourcePacks) +
        listOfNotNull(vm.qwenVoiceWeights?.takeIf { qwen && !bridgeReady && unsupported && it.installed })
    val partsInstalled = listOf(storedBridge) + packs.map { it.installed }
    val ready = partsInstalled.count { it }
    val total = partsInstalled.size
    val remaining = packs.sumOf { it.remainingBytes } + if (bridgeReady) 0L else spec.downloadBytes
    val download = vm.officialDownload.takeIf { it.packageId == spec.id }
    val enabled = !vm.modelPreview && !vm.loading && vm.connected && !vm.busy && !vm.fileBusy && vm.recheckingModel == null
    var expanded by rememberSaveable(spec.id) { mutableStateOf(false) }
    val expansion = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    val complete = ready == total
    val canExpand = !unsupported || ready > 0
    val status = when {
        unsupported || states == null || download?.running == true -> null
        expanded && remaining > 0 -> stringResource(R.string.model_remaining, Formatter.formatShortFileSize(context, remaining))
        complete -> stringResource(R.string.backend_installed)
        else -> stringResource(R.string.model_parts_ready, ready, total)
    }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "chevron")
    StudioCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = canExpand, role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = expansion }
            .heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlyphTile {
                Icon(painterResource(R.drawable.ic_models), null, Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (status != null) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (complete && !unsupported) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
                    Text(status, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = Tabular),
                        color = colors.onSurfaceVariant)
                }
            }
            if (canExpand) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(chevron), tint = colors.onSurfaceVariant)
        }
        if (!unsupported && states != null && download?.running != true && total > 1) {
            SegmentMeter(partsInstalled, Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp))
        }
        if (download?.running == true) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp)) {
                val percent = download.percent
                val track = colors.outlineVariant.copy(alpha = .6f)
                if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp).clip(CircleShape),
                    trackColor = track, gapSize = 0.dp)
                else LinearProgressIndicator(progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp).clip(CircleShape),
                    trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val message = download.label?.resolve(resources).orEmpty()
                    Text(listOfNotNull(message, percent?.let { "$it%" }).joinToString(" · "), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = Tabular),
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    StudioButton(vm::cancelOfficialDownload, style = StudioButtonStyle.Quiet) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
        if (states == null && vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp).clip(CircleShape),
                trackColor = colors.outlineVariant.copy(alpha = .6f), gapSize = 0.dp)
        }
        download?.error?.let {
            Text(it.resolve(resources), Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        AnimatedVisibility(expanded && canExpand && states != null,
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(120))) {
            Column {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (!unsupported || storedBridge) ModelPart(stringResource(R.string.model_bridge),
                        spec.downloadBytes, storedBridge,
                        enabled && (storedBridge || archive.installable),
                        updateRequired = bridgeState == BridgeInstallState.UPDATE_REQUIRED,
                        remove = { vm.removeModelPart(spec.id, VoiceBoxBackendService.BRIDGE_PART) },
                        install = { vm.installModelParts(spec.id, VoiceBoxBackendService.BRIDGE_PART) })
                    packs.filter { !unsupported || it.installed }.forEach { pack ->
                        key(pack.id) {
                            val title = if (ming) stringResource(MingModelPackage.partTitle(pack.id))
                                else UiText(pack.titleKey).resolve(resources)
                            ModelPart(title, pack.downloadBytes, pack.installed, enabled,
                                remove = { vm.removeModelPart(spec.id, pack.id) },
                                install = { vm.installModelParts(spec.id, pack.id) })
                        }
                    }
                }
            }
        }
        val actionModifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp,
            top = if (expanded) 4.dp else 0.dp)
        if (unsupported || rechecking) {
            StudioButton({ vm.recheckDownloadSupport(archive) }, enabled = enabled, style = StudioButtonStyle.Secondary,
                modifier = actionModifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (rechecking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, stringResource(R.string.model_recheck_gpu), Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant)
                Text(stringResource(if (rechecking) R.string.model_rechecking else R.string.model_device_unsupported),
                    color = colors.onSurfaceVariant)
            }
        } else if (states != null && !complete && (archive.installable || vm.modelPreview) &&
            download?.running != true) {
            StudioButton({ vm.installModelParts(spec.id, null) },
                enabled = enabled, style = StudioButtonStyle.Secondary,
                modifier = actionModifier) {
                Icon(painterResource(R.drawable.ic_model_download), null, Modifier.size(16.dp))
                Text(stringResource(R.string.model_download_all))
                if (remaining > 0) Text(Formatter.formatShortFileSize(context, remaining),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = Tabular),
                    color = LocalContentColor.current.copy(alpha = .62f))
            }
        }
    }
}

@Composable
private fun ModelPart(title: String, bytes: Long, installed: Boolean, enabled: Boolean,
    updateRequired: Boolean = false, remove: () -> Unit, install: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var confirmRemove by remember { mutableStateOf(false) }
    val action = if (installed) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button,
        onClickLabel = stringResource(if (updateRequired) R.string.model_update_part else R.string.model_install_part, title), onClick = install)
    val endPadding = 16.dp - (PartActionWidth - PartMarkSize) / 2
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 56.dp)
        .padding(start = 16.dp, end = endPadding, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val muted = colors.onSurface.copy(alpha = .55f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (installed) colors.onSurface else muted)
            if (bytes > 0) Text(Formatter.formatShortFileSize(LocalContext.current, bytes),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = Tabular),
                color = if (installed) colors.onSurfaceVariant else muted)
        }
        if (installed) {
            IconButton(onClick = { confirmRemove = true }, enabled = enabled, modifier = Modifier.size(PartActionWidth)) {
                val tint = if (enabled) colors.error else colors.error.copy(alpha = .38f)
                Box(Modifier.size(PartMarkSize).clip(CircleShape).background(tint.copy(alpha = .14f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, stringResource(R.string.model_remove_part, title), Modifier.size(15.dp), tint = tint)
                }
            }
        } else {
            Box(Modifier.width(PartActionWidth), contentAlignment = Alignment.Center) {
                Box(Modifier.size(PartMarkSize).border(1.dp, colors.outline.copy(alpha = if (enabled) .55f else .25f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (updateRequired) R.drawable.ic_model_update else R.drawable.ic_model_download),
                        null, Modifier.size(14.dp),
                        tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
                }
            }
        }
    }
    if (installed && confirmRemove) RemoveModelPartDialog(title, enabled, onDismiss = { confirmRemove = false },
        onConfirm = {
            confirmRemove = false
            remove()
        })
}

@Composable
internal fun RemoveModelPartDialog(title: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        StudioCard(Modifier.width(256.dp), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.model_remove_confirmation, title),
                    Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    StudioButton(onDismiss, style = StudioButtonStyle.Quiet) {
                        Text(stringResource(R.string.cancel))
                    }
                    StudioButton(onConfirm, enabled = enabled, style = StudioButtonStyle.Secondary) {
                        Text(stringResource(R.string.model_remove_action),
                            color = if (enabled) colors.error else LocalContentColor.current)
                    }
                }
            }
        }
    }
}
