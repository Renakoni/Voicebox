package dev.renakoni.voicebox.studio.creation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.OptionControl
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.studio.LanguageMenu
import dev.renakoni.voicebox.studio.StudioButton
import dev.renakoni.voicebox.studio.StudioButtonStyle
import dev.renakoni.voicebox.studio.StudioSlider
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun OutputLanguageToolbarMenu(settings: CreationSettings, enabled: Boolean) {
    if (!settings.usesOutputLanguage) return
    CreationToolbarMenu {
        LanguageMenu(LocalResources.current.getString(R.string.output_language), settings.outputLanguage,
            enabled && settings.hasModel, settings.languages, onSelect = settings::changeLanguage)
    }
}

@Composable
internal fun StreamingToolbarMenu(settings: CreationSettings, enabled: Boolean) {
    if (!settings.supportsStreaming) return
    CreationToolbarMenu { SynthesisModeMenu(settings.streaming, enabled, settings::changeStreaming) }
}

@Composable
private fun CreationToolbarMenu(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
internal fun AdvancedSettingsPanel(content: @Composable ColumnScope.(expanded: Boolean) -> Unit) {
    val resources = LocalResources.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    StudioSettingsHeader(resources.getString(R.string.advanced_settings), expanded) { expanded = !expanded }
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
            content(expanded)
        }
    }
}

@Composable
internal fun NumericSynthesisSettings(controls: List<OptionControl>, options: Map<String, String>, enabled: Boolean,
    onChange: (Map<String, String>) -> Unit) {
    if (controls.isEmpty()) return
    val resources = LocalResources.current
    AdvancedSettingsPanel { expanded ->
        // Keep drag previews out of requests and restore committed values on collapse.
        var draft by remember(options, enabled, expanded) { mutableStateOf(options) }
        for (control in controls) {
            val current = (draft[control.name] ?: control.defaultValue).toFloat()
            val label = UiText(control.label).resolve(resources)
            val display = if (control.integral) current.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", current)
            SynthesisSlider(label, display + control.unit, current, control.min..control.max, control.increments - 1,
                enabled, onChange = { changed ->
                    val value = if (control.integral) changed.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", changed)
                    draft = draft + (control.name to value)
                }, onFinished = { onChange(draft) })
        }
        StudioButton(onClick = { draft = emptyMap(); onChange(draft) }, enabled = enabled && controls.any {
            draft[it.name]?.toFloat()?.let { value -> value != it.defaultValue.toFloat() } == true
        }, style = StudioButtonStyle.Quiet, modifier = Modifier.align(Alignment.End)) {
            Text(resources.getString(R.string.reset_defaults))
        }
    }
}

@Composable
internal fun StudioSettingsHeader(title: String, expanded: Boolean, summary: String? = null, enabled: Boolean = true,
    onClick: () -> Unit) {
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp)
        .semantics { stateDescription = resources.getString(if (expanded) R.string.expanded else R.string.collapsed) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (summary != null) Text(summary, Modifier.widthIn(max = 160.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SynthesisModeMenu(streaming: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val resources = LocalResources.current
    var expanded by remember { mutableStateOf(false) }
    val label = resources.getString(if (streaming) R.string.ming_mode_stream else R.string.ming_mode_full)
    Box {
        StudioButton(onClick = { expanded = true }, enabled = enabled, style = StudioButtonStyle.Quiet,
            modifier = Modifier.semantics { contentDescription = resources.getString(R.string.ming_mode_selection, label) }) {
            Text(label)
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            listOf(false to R.string.ming_mode_full, true to R.string.ming_mode_stream).forEach { (mode, title) ->
                DropdownMenuItem(text = { Text(resources.getString(title)) }, onClick = {
                    onSelect(mode)
                    expanded = false
                }, trailingIcon = {
                    if (mode == streaming) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                }, modifier = Modifier.semantics { selected = mode == streaming })
            }
        }
    }
}

@Composable
private fun SynthesisSlider(label: String, displayValue: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, enabled: Boolean, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(displayValue, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StudioSlider(value, onChange, enabled = enabled, valueRange = range, steps = steps, onValueChangeFinished = onFinished,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = displayValue
            })
    }
}
