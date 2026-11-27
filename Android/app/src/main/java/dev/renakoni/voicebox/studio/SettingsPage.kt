package dev.renakoni.voicebox.studio

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.AppSettingsRepository.ThemeMode
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.DeviceFacts.NpuSupport
import dev.renakoni.voicebox.core.i18n.UiText

@Composable
internal fun SettingsPage(vm: StudioViewModel, openAbout: () -> Unit, openProject: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    Column(modifier.fillMaxHeight()) {
        PageHeader(stringResource(R.string.settings))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsGroup(stringResource(R.string.settings_general)) {
                ThemeSetting(vm.theme, vm::changeTheme)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(onClick = openAbout).heightIn(min = 56.dp).padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StarInvitation(openProject, Modifier.widthIn(max = 176.dp))
                }
            }
            SettingsGroup(stringResource(R.string.settings_acceleration)) {
                val gpuOn = vm.gpuEnabled && vm.gpuAvailable
                SettingsSwitch(stringResource(R.string.gpu_acceleration), gpuOn,
                    vm::setGpu, enabled = vm.gpuAvailable && !vm.busy)
                AnimatedVisibility(visible = gpuOn,
                    enter = expandVertically(tween(180, easing = LinearOutSlowInEasing), expandFrom = Alignment.Top) +
                        fadeIn(tween(150)),
                    exit = shrinkVertically(tween(130, easing = FastOutLinearInEasing), shrinkTowards = Alignment.Top) +
                        fadeOut(tween(90))) {
                    val device = listOf(vm.deviceFacts.soc, vm.deviceFacts.model).firstOrNull { it.isNotBlank() && it != Build.UNKNOWN }
                    val status = if (vm.deviceNpuSupport == NpuSupport.AVAILABLE) R.string.npu_device_supported
                        else R.string.npu_device_unsupported
                    SettingsSwitch(stringResource(R.string.device_acceleration), vm.deviceAccelerationEnabled,
                        vm::setDeviceAcceleration, enabled = vm.canUseDeviceAcceleration && !vm.busy,
                        detail = listOfNotNull(stringResource(status), device).joinToString(" · "))
                }
            }
            SettingsGroup(stringResource(R.string.settings_local_service)) {
                SettingsSwitch(stringResource(R.string.local_api), vm.apiEnabled, vm::setApi,
                    detail = if (vm.apiEnabled) stringResource(R.string.local_api_description) else null)
                if (vm.apiEnabled) {
                    OutlinedTextField(vm.apiPort, vm::editPort, label = { Text(stringResource(R.string.port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = vm.apiPortError != null,
                        trailingIcon = {
                            IconButton(onClick = vm::applyPort) {
                                Icon(Icons.Default.Check, stringResource(R.string.save_port), Modifier.size(20.dp))
                            }
                        },
                        supportingText = vm.apiPortError?.let { error -> ({
                            DismissErrorAfterTimeout(error, vm::clearPortError)
                            Text(error.resolve(resources))
                        }) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp))
                }
            }
        }
    }
}

private val ThemeMode.label get() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionLabel(title)
        StudioCard(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun ThemeSetting(theme: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { expanded = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.appearance), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(theme.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded, { expanded = false }, shape = MaterialTheme.shapes.medium) {
                ThemeMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(stringResource(mode.label)) }, onClick = {
                        expanded = false
                        onSelect(mode)
                    }, modifier = Modifier.semantics { selected = theme == mode },
                        trailingIcon = { if (theme == mode) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) })
                }
            }
        }
    }
}

@Composable
internal fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true,
    detail: String? = null, horizontalPadding: Dp = 16.dp) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
