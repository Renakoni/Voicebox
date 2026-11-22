package dev.renakoni.voicebox.studio.creation.ming

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.studio.StudioButton
import dev.renakoni.voicebox.studio.StudioButtonStyle
import dev.renakoni.voicebox.studio.creation.ChoiceChips
import dev.renakoni.voicebox.studio.creation.ControlLabel
import dev.renakoni.voicebox.studio.creation.SegmentedChoice
import dev.renakoni.voicebox.studio.creation.SheetSection
import dev.renakoni.voicebox.studio.languageName
import kotlin.random.Random

private const val DefaultMingSeed = 1234
private val FieldShape = RoundedCornerShape(12.dp)

@Composable
internal fun MingSynthesisSettings(options: Map<String, String>, presetActive: Boolean, outputLanguage: String,
    languages: List<String>, onLanguageChange: (String) -> Unit, onChange: (Map<String, String>) -> Unit) {
    val resources = LocalResources.current
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

    fun labels(vararg choices: Pair<String, Int>) = choices.map { (key, label) -> key to resources.getString(label) }

    // Speed, pitch and volume combine; emotion, dialect and instruction each replace every other control.
    fun select(key: String, value: String?) {
        val group = if (key in basic) basic else setOf(key)
        onChange(if (value != null) (options - (controlFields - group)) + (key to value) else options - key)
    }
    // Tapping the active choice again clears it back to "unspecified".
    fun toggle(key: String, current: String?) = { chosen: String -> select(key, chosen.takeUnless { it == current }) }

    SeedSection(options, onChange)

    if (!presetActive) {
        SheetSection(stringResource(R.string.ming_voice_shaping), stringResource(R.string.ming_controls_exclusive)) {
            val levels = labels("low" to R.string.ming_pitch_low, "medium" to R.string.ming_pitch_medium, "high" to R.string.ming_pitch_high)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlLabel(stringResource(R.string.speech_speed))
                SegmentedChoice(labels("slow" to R.string.ming_speed_slow, "normal" to R.string.ming_speed_normal,
                    "fast" to R.string.ming_speed_fast), speed, onSelect = toggle("speed", speed))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlLabel(stringResource(R.string.ming_pitch))
                SegmentedChoice(levels, pitch, onSelect = toggle("pitch", pitch))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlLabel(stringResource(R.string.ming_volume))
                SegmentedChoice(levels, volume, onSelect = toggle("volume", volume))
            }
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ControlLabel(stringResource(R.string.ming_emotion))
                ChoiceChips(labels("neutral" to R.string.ming_emotion_neutral, "happy" to R.string.ming_emotion_happy,
                    "sad" to R.string.ming_emotion_sad, "angry" to R.string.ming_emotion_angry,
                    "fearful" to R.string.ming_emotion_fearful, "disgusted" to R.string.ming_emotion_disgusted,
                    "surprised" to R.string.ming_emotion_surprised), emotion, onSelect = toggle("emotion", emotion))
            }
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ControlLabel(stringResource(R.string.ming_dialect))
                ChoiceChips(labels("cantonese" to R.string.ming_dialect_cantonese, "chuanyu dialect" to R.string.ming_dialect_chuanyu),
                    dialect, onSelect = toggle("dialect", dialect))
            }
        }
        val replaced = when {
            hasBasic -> R.string.ming_basic_controls
            emotion != null -> R.string.ming_emotion
            dialect != null -> R.string.ming_dialect
            else -> null
        }
        InstructionSection(instruction, options["use_reference"] == "true", replaced) { text, withVoice ->
            var next = options - instructionFields
            if (text.isNotBlank()) {
                next = (options - controlFields) + ("instruction" to text)
                if (withVoice) next = next + ("use_reference" to "true")
            }
            onChange(next)
        }
    }

    if (languages.isNotEmpty()) {
        SheetSection(stringResource(R.string.output_language)) {
            val choices = labels("" to R.string.ming_language_from_text) + languages.map { it to languageName(resources, it) }
            SegmentedChoice(choices, outputLanguage, onSelect = onLanguageChange)
        }
    }
}

@Composable
private fun SeedSection(options: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val seed = options["seed"] ?: DefaultMingSeed.toString()
    val seedLabel = stringResource(R.string.ming_seed)
    val seedError = stringResource(R.string.ming_seed_range)
    var seedDraft by remember { mutableStateOf<TextFieldValue?>(null) }
    var seedFocused by remember { mutableStateOf(false) }
    var invalidSeed by remember { mutableStateOf(false) }
    val edge by animateColorAsState(when {
        invalidSeed -> colors.error
        seedFocused -> colors.primary
        else -> colors.outlineVariant.copy(alpha = 0f)
    }, tween(160), label = "seed edge")

    SheetSection(seedLabel, stringResource(R.string.ming_seed_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        onChange(next)
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp)
                    .onFocusChanged {
                        val focused = it.isFocused
                        if (focused && !seedFocused) seedDraft = TextFieldValue(seed, selection = TextRange(0, seed.length))
                        seedFocused = focused
                        if (!focused) {
                            seedDraft = null
                            invalidSeed = false
                        }
                    }
                    .semantics {
                        contentDescription = seedLabel
                        if (invalidSeed) error(seedError)
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = colors.onSurface,
                    fontFeatureSettings = "tnum", letterSpacing = .5.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize().clip(FieldShape).background(colors.surfaceContainerHigh)
                        .border(1.dp, edge, FieldShape).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart) { input() }
                },
            )
            StudioButton(onClick = {
                focus.clearFocus()
                val next = Random.nextInt(0, 100_000)
                onChange(if (next == DefaultMingSeed) options - "seed" else options + ("seed" to next.toString()))
            }, style = StudioButtonStyle.Secondary, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Text(stringResource(R.string.ming_seed_shuffle))
            }
        }
        if (invalidSeed) Text(seedError, style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
}

@Composable
private fun InstructionSection(value: String?, useReference: Boolean, replacesControl: Int?,
    onChange: (String, Boolean) -> Unit) {
    val title = stringResource(R.string.ming_instruction)
    val colors = MaterialTheme.colorScheme
    val caption = if (replacesControl != null) stringResource(R.string.ming_instruction_conflict, stringResource(replacesControl))
        else null
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(if (focused) colors.primary else colors.outlineVariant.copy(alpha = 0f),
        tween(160), label = "instruction edge")
    SheetSection(title, caption) {
        val writingStyle = MaterialTheme.typography.bodyLarge
        BasicTextField(
            value.orEmpty(), { if (it.length <= 1024) onChange(it, useReference) },
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = title },
            textStyle = writingStyle.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary), minLines = 3, maxLines = 6,
            decorationBox = { input ->
                Box(Modifier.fillMaxWidth().clip(FieldShape).background(colors.surfaceContainerHigh)
                    .border(1.dp, edge, FieldShape).padding(14.dp)) {
                    if (value.isNullOrEmpty()) Text(stringResource(R.string.ming_instruction_hint),
                        style = writingStyle, color = colors.onSurfaceVariant)
                    input()
                }
            },
        )
        val toggleEnabled = value != null
        Row(Modifier.fillMaxWidth().clip(FieldShape)
            .toggleable(useReference, toggleEnabled, Role.Switch) { onChange(value.orEmpty(), it) }
            .heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ming_use_reference), style = MaterialTheme.typography.bodyMedium,
                    color = if (toggleEnabled) colors.onSurface else colors.onSurfaceVariant)
                Text(stringResource(R.string.ming_use_reference_hint), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Switch(useReference, onCheckedChange = null, enabled = toggleEnabled)
        }
    }
}
