package dev.renakoni.voicebox.studio.creation.ming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.studio.creation.StudioSettingsHeader
import dev.renakoni.voicebox.studio.languageName
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset

@Composable
internal fun MingVoicePresetSetting(presets: List<SynthesisVoicePreset>, active: SynthesisVoicePreset?, enabled: Boolean,
    onSelect: (String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val reference = stringResource(R.string.ming_preset_reference)
    val select: (String?) -> Unit = {
        onSelect(it)
        browsing = false
        expanded = false
    }
    StudioSettingsHeader(stringResource(R.string.ming_voice), expanded, active?.id ?: reference, enabled) {
        expanded = !expanded
    }
    if (expanded) {
        PresetVoiceRow(reference, active == null, enabled) { select(null) }
        presets.filter { it.recommended }.forEach { preset ->
            PresetVoiceRow(preset.id, active?.id == preset.id, enabled) { select(preset.id) }
        }
        TextButton(onClick = { browsing = true }, enabled = enabled) { Text(stringResource(R.string.ming_preset_browse)) }
    }
    if (browsing && enabled) {
        MingPresetBrowser(presets, active?.id, { browsing = false }, { select(it) })
    }
}

@Composable
private fun MingPresetBrowser(presets: List<SynthesisVoicePreset>, selectedId: String?, onDismiss: () -> Unit,
    onSelect: (String) -> Unit) {
    val resources = LocalResources.current
    var search by rememberSaveable { mutableStateOf("") }
    var expandedBase by rememberSaveable(selectedId) { mutableStateOf(presets.firstOrNull { it.id == selectedId }?.baseId) }
    val groups = remember(presets) { presets.groupBy { it.baseId } }
    val query = search.trim().replace(' ', '_')
    fun matches(id: String) = id.replace(' ', '_').contains(query, ignoreCase = true)
    val baseVoices = groups[null].orEmpty().filter { voice ->
        matches(voice.id) || groups[voice.id].orEmpty().any { matches(it.id) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ming_preset_browse)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ming_preset_search)) })
                if (baseVoices.isEmpty()) Text(stringResource(R.string.ming_preset_no_results))
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(baseVoices, key = { it.id }) { voice ->
                        val variants = groups[voice.id].orEmpty()
                        val shown = expandedBase == voice.id || !matches(voice.id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PresetVoiceRow(voice.id, voice.id == selectedId, enabled = true,
                                modifier = Modifier.weight(1f), detail = languageName(resources, voice.language)) { onSelect(voice.id) }
                            if (variants.isNotEmpty() && matches(voice.id)) IconButton(onClick = { expandedBase = if (shown) null else voice.id }) {
                                Icon(if (shown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    stringResource(R.string.ming_preset_variants))
                            }
                        }
                        if (shown) variants.forEach { variant ->
                            if (matches(voice.id) || matches(variant.id)) {
                                PresetVoiceRow(variant.id, variant.id == selectedId, enabled = true,
                                    modifier = Modifier.padding(start = 16.dp)) { onSelect(variant.id) }
                            }
                        }
                    }
                }
            }
        })
}

@Composable
private fun PresetVoiceRow(name: String, isSelected: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    detail: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).semantics { selected = isSelected }
        .heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (isSelected) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = colors.primary)
    }
}
