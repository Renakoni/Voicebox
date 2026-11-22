@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renakoni.voicebox.studio.creation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.OptionControl
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.studio.LanguageMenu
import dev.renakoni.voicebox.studio.StudioButton
import dev.renakoni.voicebox.studio.StudioButtonStyle
import dev.renakoni.voicebox.studio.StudioCard
import dev.renakoni.voicebox.studio.StudioSlider
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Toolbar controls (rendered in the generate dock, next to the primary action)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun OutputLanguageToolbarMenu(settings: CreationSettings, enabled: Boolean) {
    if (!settings.usesOutputLanguage) return
    LanguageMenu(LocalResources.current.getString(R.string.output_language), settings.outputLanguage,
        enabled && settings.hasModel, settings.languages, onSelect = settings::changeLanguage)
}

@Composable
internal fun StreamingToolbarMenu(settings: CreationSettings, enabled: Boolean) {
    if (!settings.supportsStreaming) return
    val resources = LocalResources.current
    val label = resources.getString(if (settings.streaming) R.string.ming_mode_stream else R.string.ming_mode_full)
    SegmentedChoice(
        choices = listOf("full" to resources.getString(R.string.ming_mode_full),
            "stream" to resources.getString(R.string.ming_mode_stream)),
        selected = if (settings.streaming) "stream" else "full",
        enabled = enabled, height = 48.dp, fill = false,
        modifier = Modifier.semantics { contentDescription = resources.getString(R.string.ming_mode_selection, label) },
    ) { settings.changeStreaming(it == "stream") }
}

// ---------------------------------------------------------------------------------------------
// Selection primitives
// ---------------------------------------------------------------------------------------------

private val SegmentTrackShape = RoundedCornerShape(12.dp)
private val SegmentShape = RoundedCornerShape(9.dp)

/**
 * A quiet segmented control: a recessed track holding equal segments; the chosen one lifts
 * into a soft clay tint. Callers decide whether tapping the chosen segment again clears it.
 */
@Composable
internal fun SegmentedChoice(choices: List<Pair<String, String>>, selected: String?, enabled: Boolean = true,
    modifier: Modifier = Modifier, height: Dp = 44.dp, fill: Boolean = true, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val track = colors.surfaceContainerHigh
    Row(modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).height(height)
        .clip(SegmentTrackShape).background(track).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((key, label) in choices) {
            val on = key == selected
            val background by animateColorAsState(
                if (on) colors.primary.copy(alpha = .2f).compositeOver(track) else track, tween(180), label = "segment")
            val content = when {
                !enabled -> colors.onSurface.copy(alpha = .38f)
                on -> colors.primary
                else -> colors.onSurfaceVariant
            }
            Box(Modifier.then(if (fill) Modifier.weight(1f) else Modifier).fillMaxHeight()
                .clip(SegmentShape).background(background)
                .selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(key) }
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Pill chips for longer option sets. Tapping the chosen chip calls [onSelect] with the same key. */
@Composable
internal fun ChoiceChips(choices: List<Pair<String, String>>, selected: String?,
    modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, label) in choices) {
            val on = key == selected
            val background by animateColorAsState(
                if (on) colors.primary.copy(alpha = .18f) else colors.surfaceContainerHigh.copy(alpha = 0f),
                tween(180), label = "chip")
            Box(Modifier.heightIn(min = 36.dp).clip(CircleShape).background(background)
                .border(1.dp, if (on) colors.primary.copy(alpha = .45f) else colors.outlineVariant, CircleShape)
                .selectable(on, role = Role.RadioButton) { onSelect(key) }
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.primary else colors.onSurface)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings sheet
// ---------------------------------------------------------------------------------------------

/**
 * Entry row for model-specific synthesis settings. The settings themselves live in a bottom
 * sheet so the writing surface stays calm; the row only reports what has been changed.
 */
@Composable
internal fun AdvancedSettingsPanel(summary: String?, enabled: Boolean, onReset: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val resources = LocalResources.current
    var open by rememberSaveable { mutableStateOf(false) }
    val title = resources.getString(R.string.advanced_settings)
    SettingsEntryRow(title,
        summary ?: resources.getString(R.string.settings_default), highlighted = summary != null, enabled = enabled) { open = true }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    if (open && enabled) SettingsSheet(title, canReset = summary != null, onReset = onReset,
        onDismiss = { open = false }, content = content)
}

@Composable
private fun SettingsEntryRow(title: String, value: String, highlighted: Boolean, enabled: Boolean,
    onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    StudioCard(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .5f)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(value, Modifier.widthIn(max = 180.dp), style = MaterialTheme.typography.bodyMedium,
                color = if (highlighted) colors.primary else colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSheet(title: String, canReset: Boolean, onReset: () -> Unit, onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp)
                .clip(CircleShape).background(colors.outlineVariant))
        }) {
        val focus = LocalFocusManager.current
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.2).sp))
            StudioButton(onClick = { focus.clearFocus(force = true); onReset() }, enabled = canReset, style = StudioButtonStyle.Quiet) {
                Text(LocalResources.current.getString(R.string.reset_defaults))
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** A titled card inside the settings sheet. */
@Composable
internal fun SheetSection(title: String, caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Small label above a control inside a [SheetSection]. */
@Composable
internal fun ControlLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------------------------------------
// Numeric sliders (GPT-SoVITS and generic backends)
// ---------------------------------------------------------------------------------------------

internal fun numericOverrides(controls: List<OptionControl>, options: Map<String, String>) = controls.count {
    options[it.name]?.toFloatOrNull()?.let { value -> value != it.defaultValue.toFloat() } == true
}

@Composable
internal fun NumericSynthesisSettings(controls: List<OptionControl>, options: Map<String, String>,
    onChange: (Map<String, String>) -> Unit) {
    val resources = LocalResources.current
    // Keep drag previews out of requests and restore committed values when the sheet closes.
    var draft by remember(options) { mutableStateOf(options) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (control in controls) {
            val current = (draft[control.name] ?: control.defaultValue).toFloat()
            val label = UiText(control.label).resolve(resources)
            val display = if (control.integral) current.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", current)
            SynthesisSlider(label, display + control.unit, current, control.min..control.max, control.increments - 1,
                onChange = { changed ->
                    val value = if (control.integral) changed.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", changed)
                    draft = draft + (control.name to value)
                }, onFinished = { onChange(draft) })
        }
    }
}

@Composable
private fun SynthesisSlider(label: String, displayValue: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column {
        ControlLabel(label, displayValue)
        StudioSlider(value, onChange, valueRange = range, steps = steps, onValueChangeFinished = onFinished,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = displayValue
            })
    }
}
