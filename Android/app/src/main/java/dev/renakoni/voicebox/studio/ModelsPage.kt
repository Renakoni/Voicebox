package dev.renakoni.voicebox.studio

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.VoiceBoxBackendService
import dev.renakoni.voicebox.core.BridgePackage
import dev.renakoni.voicebox.core.i18n.UiText

@Composable
internal fun ModelsPage(vm: StudioViewModel, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        PageHeader(stringResource(R.string.models))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (vm.installedPackages == null && !vm.loading && vm.connected) {
                TextButton(vm::refreshResources, Modifier.padding(horizontal = 8.dp), enabled = !vm.busy && !vm.fileBusy) {
                    Text(stringResource(R.string.model_reload))
                }
            }
            vm.backendPackages.forEachIndexed { index, archive ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant)
                ModelFamily(vm, archive)
            }
        }
    }
}

@Composable
private fun ModelFamily(vm: StudioViewModel, archive: BridgePackage) {
    val spec = archive.spec
    if (!archive.supported) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(spec.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.model_device_unsupported), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val resources = LocalResources.current
    val context = LocalContext.current
    val installed = vm.installedPackages
    val bridgeReady = installed?.contains(spec.id) == true
    val packs = vm.resourcePacks[spec.id].orEmpty()
    val ready = (if (bridgeReady) 1 else 0) + packs.count { it.installed }
    val remaining = packs.sumOf { it.remainingBytes } + if (spec.bundled || bridgeReady) 0L else spec.downloadBytes
    val download = vm.officialDownload.takeIf { it.packageId == spec.id }
    val enabled = !vm.loading && vm.connected && !vm.busy && !vm.fileBusy
    var expanded by rememberSaveable(spec.id) { mutableStateOf(spec.bundled) }
    val expansion = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    val status = when {
        installed == null || download?.running == true -> null
        expanded && remaining > 0 -> stringResource(R.string.model_remaining, Formatter.formatShortFileSize(context, remaining))
        ready == packs.size + 1 -> stringResource(R.string.backend_installed)
        else -> stringResource(R.string.model_parts_ready, ready, packs.size + 1)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = if (expanded) 8.dp else 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = expansion }.heightIn(min = 64.dp).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spec.displayName, Modifier.weight(1f, fill = false).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (installed != null && ready < packs.size + 1 && download?.running != true) {
                TextButton({ vm.installModelParts(spec.id, null) }, enabled = enabled) { Text(stringResource(R.string.model_download_all)) }
            }
        }
        if (download?.running == true) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val percent = download.percent
                if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val message = download.label?.resolve(resources).orEmpty()
                    Text(listOfNotNull(message, percent?.let { "$it%" }).joinToString(" · "), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(vm::cancelOfficialDownload) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
        if (installed == null && vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        download?.error?.let {
            Text(it.resolve(resources), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (expanded && installed != null) {
            ModelPart(stringResource(R.string.model_bridge),
                if (spec.bundled && !bridgeReady) 0L else spec.downloadBytes, bridgeReady, enabled) {
                vm.installModelParts(spec.id, VoiceBoxBackendService.BRIDGE_PART)
            }
            packs.forEach { pack ->
                ModelPart(UiText(pack.titleKey).resolve(resources),
                    if (pack.installed) pack.downloadBytes else pack.remainingBytes, pack.installed, enabled) {
                    vm.installModelParts(spec.id, pack.id)
                }
            }
        }
    }
}

@Composable
private fun ModelPart(title: String, bytes: Long, installed: Boolean, enabled: Boolean, install: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val size = if (bytes == 0L && !installed) stringResource(R.string.model_prepare)
        else Formatter.formatShortFileSize(LocalContext.current, bytes)
    val action = if (installed) Modifier.semantics(mergeDescendants = true) {} else
        Modifier.clickable(enabled = enabled, role = Role.Button,
            onClickLabel = stringResource(R.string.model_install_part, title), onClick = install)
    val tint = if (installed || enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f)
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 48.dp).padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(size, color = tint, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        if (installed) Icon(Icons.Default.Check, stringResource(R.string.backend_installed), Modifier.size(16.dp), tint = tint)
        else Icon(painterResource(R.drawable.ic_model_download), null, Modifier.size(16.dp), tint = tint)
    }
}
