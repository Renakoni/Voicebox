package dev.renakoni.voicebox.studio.creation.ming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.studio.StudioButton
import dev.renakoni.voicebox.studio.StudioButtonStyle
import dev.renakoni.voicebox.studio.StudioControlShape
import dev.renakoni.voicebox.studio.languageName

private const val DefaultMingSeed = 1234

@Composable
internal fun MingSynthesisSettings(options: Map<String, String>, enabled: Boolean, presetActive: Boolean, outputLanguage: String,
    languages: List<String>, onLanguageChange: (String) -> Unit, onChange: (Map<String, String>) -> Unit, onReset: () -> Unit) {
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val seedFocus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val seed = options["seed"] ?: DefaultMingSeed.toString()
    val seedLabel = stringResource(R.string.ming_seed)
    val seedError = stringResource(R.string.ming_seed_range)
    var seedDraft by remember { mutableStateOf<TextFieldValue?>(null) }
    var seedFocused by remember { mutableStateOf(false) }
    var invalidSeed by remember { mutableStateOf(false) }
    val speed = options["speed"]
    val pitch = options["pitch"]
    val volume = options["volume"]
    val emotion = options["emotion"]
    val dialect = options["dialect"]
    val instruction = options["instruction"]
    val basic = setOf("speed", "pitch", "volume")
    val instructionFields = setOf("instruction", "use_reference")
    val controlFields = basic + instructionFields + setOf("emotion", "dialect")
    val hasBasic = basic.any { it in options }
    val conflict = R.string.ming_replaces_control

    fun labels(vararg choices: Pair<String, Int>) = choices.map { (key, label) -> key to resources.getString(label) }

    fun select(key: String, value: String?) {
        val group = if (key in basic) basic else setOf(key)
        onChange(if (value != null) (options - (controlFields - group)) + (key to value) else options - key)
    }

    Column {
        Row(Modifier.fillMaxWidth().clip(StudioControlShape).clickable(enabled = enabled, role = Role.Button) {
            seedDraft = TextFieldValue(seed, selection = TextRange(0, seed.length))
            invalidSeed = false
            seedFocus.requestFocus()
            keyboard?.show()
        }.heightIn(min = 48.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(seedLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = seedDraft ?: TextFieldValue(seed),
                onValueChange = { edited ->
                    val value = edited.text
                    val number = value.toIntOrNull()
                    invalidSeed = value.isNotEmpty() && (number == null || value.any { it !in '0'..'9' })
                    if (!invalidSeed) {
                        seedDraft = edited
                        val next = if (number == null || number == DefaultMingSeed) options - "seed"
                            else options + ("seed" to number.toString())
                        if (next != options) onChange(next)
                    }
                },
                modifier = Modifier.width(104.dp).heightIn(min = 48.dp).focusRequester(seedFocus)
                    .onFocusChanged {
                        seedFocused = it.isFocused
                        if (!seedFocused) {
                            seedDraft = null
                            invalidSeed = false
                        }
                    }
                    .semantics {
                        contentDescription = seedLabel
                        if (invalidSeed) error(seedError)
                    },
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                    textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                decorationBox = { input ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        input()
                        if (seedFocused) {
                            HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
                                color = if (invalidSeed) colors.error else colors.primary)
                        }
                    }
                },
            )
            Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        if (invalidSeed) Text(seedError, style = MaterialTheme.typography.bodySmall, color = colors.error)

        if (!presetActive) {
            val basicConflict = conflict.takeIf { emotion != null || dialect != null || instruction != null }
            MingOptionMenu(R.string.speech_speed, speed, enabled,
                labels("slow" to R.string.ming_speed_slow, "normal" to R.string.ming_speed_normal, "fast" to R.string.ming_speed_fast),
                clearLabel = R.string.ming_clear_speed, conflictLabel = basicConflict) {
                select("speed", it)
            }
            MingOptionMenu(R.string.ming_pitch, pitch, enabled,
                labels("low" to R.string.ming_pitch_low, "medium" to R.string.ming_pitch_medium, "high" to R.string.ming_pitch_high),
                clearLabel = R.string.ming_clear_pitch, conflictLabel = basicConflict) {
                select("pitch", it)
            }
            MingOptionMenu(R.string.ming_volume, volume, enabled,
                labels("low" to R.string.ming_pitch_low, "medium" to R.string.ming_pitch_medium, "high" to R.string.ming_pitch_high),
                clearLabel = R.string.ming_clear_volume, conflictLabel = basicConflict) {
                select("volume", it)
            }
            MingOptionMenu(R.string.ming_emotion, emotion, enabled,
                labels("neutral" to R.string.ming_emotion_neutral, "happy" to R.string.ming_emotion_happy, "sad" to R.string.ming_emotion_sad,
                    "angry" to R.string.ming_emotion_angry, "fearful" to R.string.ming_emotion_fearful,
                    "disgusted" to R.string.ming_emotion_disgusted, "surprised" to R.string.ming_emotion_surprised),
                clearLabel = R.string.ming_clear_emotion, conflictLabel = conflict.takeIf { hasBasic || dialect != null || instruction != null }) {
                select("emotion", it)
            }
            MingOptionMenu(R.string.ming_dialect, dialect, enabled,
                labels("cantonese" to R.string.ming_dialect_cantonese, "chuanyu dialect" to R.string.ming_dialect_chuanyu),
                clearLabel = R.string.ming_clear_dialect, conflictLabel = conflict.takeIf { hasBasic || emotion != null || instruction != null }) {
                select("dialect", it)
            }
        }
        if (languages.isNotEmpty()) {
            val choices = labels("" to R.string.ming_language_from_text) + languages.map { it to languageName(resources, it) }
            MingOptionMenu(R.string.output_language, outputLanguage, enabled, choices) {
                onLanguageChange(it.orEmpty())
            }
        }
        if (!presetActive) {
            val replaced = when {
                hasBasic -> R.string.ming_basic_controls
                emotion != null -> R.string.ming_emotion
                dialect != null -> R.string.ming_dialect
                else -> null
            }
            MingInstructionSetting(instruction, options["use_reference"] == "true", enabled, replaced) { text, withVoice ->
                var next = options - instructionFields
                if (text.isNotBlank()) {
                    next = (options - controlFields) + ("instruction" to text)
                    if (withVoice) next = next + ("use_reference" to "true")
                }
                if (next != options) onChange(next)
            }
        }
        StudioButton(onClick = {
            focus.clearFocus()
            seedDraft = null
            invalidSeed = false
            onReset()
        }, enabled = enabled && (options.isNotEmpty() || outputLanguage.isNotEmpty() || invalidSeed),
            style = StudioButtonStyle.Quiet, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.reset_defaults))
        }
    }
}

@Composable
private fun MingInstructionSetting(value: String?, useReference: Boolean, enabled: Boolean, replacesControl: Int?,
    onChange: (String, Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.ming_instruction)
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val expansionState = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    Row(Modifier.fillMaxWidth().clip(StudioControlShape).clickable(enabled = enabled, role = Role.Button) {
        if (expanded) focus.clearFocus()
        expanded = !expanded
    }
        .semantics { stateDescription = expansionState }
        .heightIn(min = 48.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!expanded) {
            Text(value ?: stringResource(R.string.ming_option_unspecified), Modifier.widthIn(max = 144.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
            Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
    if (expanded) {
        OutlinedTextField(value.orEmpty(), { if (it.length <= 1024) onChange(it, useReference) },
            Modifier.fillMaxWidth().semantics { contentDescription = title }, enabled = enabled,
            placeholder = { Text(stringResource(R.string.ming_instruction_hint)) }, minLines = 2, maxLines = 4)
        if (replacesControl != null) {
            Text(stringResource(R.string.ming_instruction_conflict, stringResource(replacesControl)),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        MingToggle(R.string.ming_use_reference, R.string.ming_use_reference_hint, useReference, enabled && value != null) {
            onChange(value.orEmpty(), it)
        }
    }
}

@Composable
private fun MingToggle(title: Int, hint: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(StudioControlShape).toggleable(checked, enabled, Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(top = 4.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun MingOptionMenu(title: Int, value: String?, enabled: Boolean, choices: List<Pair<String, String>>,
    clearLabel: Int? = null, conflictLabel: Int? = null, onChange: (String?) -> Unit) {
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val valueName = choices.firstOrNull { it.first == value }?.second ?: stringResource(R.string.ming_option_unspecified)
    var expanded by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().clip(StudioControlShape).clickable(enabled = enabled, role = Role.Button) {
        focus.clearFocus()
        expanded = true
    }.heightIn(min = 48.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(valueName, style = MaterialTheme.typography.bodyMedium,
                    color = if (value.isNullOrEmpty() || !enabled) colors.onSurfaceVariant else colors.onSurface)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, shape = MaterialTheme.shapes.medium) {
                if (conflictLabel != null) {
                    Text(stringResource(conflictLabel), Modifier.widthIn(max = 216.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                for ((key, name) in choices) {
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        expanded = false
                        if (value != key) onChange(key)
                    }, modifier = Modifier.semantics { selected = value == key }, trailingIcon = {
                        if (value == key) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = colors.primary)
                    })
                }
                if (value != null && clearLabel != null) {
                    HorizontalDivider(color = colors.outlineVariant)
                    DropdownMenuItem(text = { Text(stringResource(clearLabel)) }, onClick = {
                        expanded = false
                        onChange(null)
                    })
                }
            }
        }
    }
}
