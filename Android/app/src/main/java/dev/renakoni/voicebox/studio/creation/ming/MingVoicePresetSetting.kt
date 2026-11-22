package dev.renakoni.voicebox.studio.creation.ming

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.studio.RingedAvatar
import dev.renakoni.voicebox.studio.VoiceTile
import dev.renakoni.voicebox.studio.groupedShape
import dev.renakoni.voicebox.studio.languageName

/** Row inside the voice card: which official Ming voice (if any) drives the synthesis. */
@Composable
internal fun MingVoicePresetSetting(presets: List<SynthesisVoicePreset>, active: SynthesisVoicePreset?, enabled: Boolean,
    onSelect: (String?) -> Unit) {
    var browsing by rememberSaveable(presets) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val title = stringResource(R.string.ming_official_voices)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) {
        focus.clearFocus()
        browsing = true
    }.heightIn(min = 52.dp).padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(active?.let { selectionName(resources, it) } ?: stringResource(R.string.ming_preset_none),
            Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active != null) FontWeight.Medium else FontWeight.Normal,
            color = if (active != null) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }

    LaunchedEffect(enabled) { if (!enabled) browsing = false }
    if (browsing && enabled) {
        MingPresetBrowser(presets, active, onBack = { browsing = false }) { id ->
            browsing = false
            onSelect(id)
        }
    }
}

@Composable
private fun MingPresetBrowser(presets: List<SynthesisVoicePreset>, active: SynthesisVoicePreset?,
    onBack: () -> Unit, onSelect: (String?) -> Unit) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val colors = MaterialTheme.colorScheme
    var search by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    val activeBase = active?.baseId ?: active?.id
    val descriptions = remember(configuration) {
        resources.getStringArray(R.array.ming_voice_descriptions).associate { entry ->
            val (id, detail) = entry.split('|', limit = 2)
            id to detail
        }
    }
    val groups = remember(presets) { presets.groupBy { it.baseId } }
    val voices = remember(groups, activeBase) {
        groups[null].orEmpty().sortedWith(
            compareByDescending<SynthesisVoicePreset> { it.id == activeBase }.thenByDescending { it.recommended },
        )
    }
    val languages = remember(voices) { voices.map { it.language }.distinct().sortedDescending() }
    val listState = rememberLazyListState()
    val results = remember(voices, groups, descriptions, language, search) {
        val query = search.trim().replace('_', ' ').split(Regex("\\s+")).filter(String::isNotEmpty)
        voices.filter { voice ->
            if (language != null && voice.language != language) return@filter false
            val detail = descriptions[voice.id].orEmpty()
            val text = "${voice.id.replace('_', ' ')} $detail ${languageName(resources, voice.language)}"
            query.all { text.contains(it, ignoreCase = true) } || groups[voice.id].orEmpty().any { variant ->
                val versionText = "$text ${variant.id.replace('_', ' ')} ${emotionName(resources, variant)}"
                query.all { versionText.contains(it, ignoreCase = true) }
            }
        }
    }
    LaunchedEffect(search, language) { listState.scrollToItem(0) }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val light = colors.background.luminance() > .5f
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        focus.clearFocus()
                        keyboard?.hide()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_create)) }
                    Text(stringResource(R.string.ming_official_voices), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.2).sp))
                    if (active != null) TextButton(onClick = { onSelect(null) }) {
                        Text(stringResource(R.string.ming_preset_clear), color = colors.onSurfaceVariant)
                    }
                }
                MingVoiceSearch(search, { search = it }, Modifier.padding(horizontal = 16.dp))
                if (languages.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tab in listOf(null) + languages) {
                            val on = tab == language
                            val title = if (tab == null) stringResource(R.string.ming_preset_all) else languageName(resources, tab)
                            Box(Modifier.heightIn(min = 34.dp).clip(CircleShape)
                                .background(if (on) colors.onSurface else colors.surfaceContainerHigh)
                                .selectable(on, role = Role.Tab, onClick = { language = tab })
                                .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text(title, style = MaterialTheme.typography.labelLarge,
                                    color = if (on) colors.surface else colors.onSurfaceVariant,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                            }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).clipToBounds(), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (languages.size > 1) 8.dp else 16.dp, bottom = 24.dp)) {
                    if (results.isEmpty()) item {
                        Text(stringResource(R.string.ming_preset_no_results), Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    itemsIndexed(results, key = { _, voice -> voice.id }) { index, voice ->
                        val checked = activeBase == voice.id
                        val variants = groups[voice.id].orEmpty()
                        val name = voiceName(voice.id, voice.language)
                        VoiceTile(groupedShape(index, results.size), checked, enabled = true,
                            onClick = { onSelect(if (checked) null else voice.id) },
                            avatar = { RingedAvatar(name, "", checked) },
                            trailing = {
                                if (variants.isNotEmpty()) MingPresetEmotionMenu(voice, variants, active?.id, checked, onSelect)
                                else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (checked) Icon(Icons.Default.Check, stringResource(R.string.selected), Modifier.size(20.dp),
                                        tint = colors.primary)
                                }
                            }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (languages.size > 1 && language == null) Text(languageName(resources, voice.language),
                                    Modifier.clip(CircleShape).background(colors.surfaceContainerHigh)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            descriptions[voice.id]?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (checked && active?.baseId != null) Text(emotionName(resources, active),
                                style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        }
                    }
                }
            }
        }
    }
}

/** A small outlined pill that opens the emotion variants of one voice. */
@Composable
private fun MingPresetEmotionMenu(voice: SynthesisVoicePreset, variants: List<SynthesisVoicePreset>, selectedId: String?,
    voiceChecked: Boolean, onSelect: (String?) -> Unit) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(false) }
    val action = stringResource(R.string.ming_preset_voice_emotions, voiceName(voice.id, voice.language))
    val state = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    Box(Modifier.padding(end = 4.dp)) {
        Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
            focus.clearFocus()
            keyboard?.hide()
            expanded = true
        }.semantics {
            contentDescription = action
            stateDescription = state
        }, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape)
                .border(1.dp, if (voiceChecked) colors.primary.copy(alpha = .45f) else colors.outlineVariant, CircleShape)
                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ming_emotion), style = MaterialTheme.typography.labelMedium,
                    color = if (voiceChecked) colors.primary else colors.onSurfaceVariant)
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp),
                    tint = if (voiceChecked) colors.primary else colors.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 148.dp).heightIn(max = 408.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .7f)),
        ) {
            for ((index, preset) in (listOf(voice) + variants).withIndex()) {
                val checked = selectedId == preset.id
                val label = if (preset.baseId == null) stringResource(R.string.ming_preset_original) else emotionName(resources, preset)
                if (index == 1) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = colors.outlineVariant.copy(alpha = .7f))
                }
                DropdownMenuItem(
                    text = {
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = if (checked) colors.primary else colors.onSurface)
                    },
                    onClick = {
                        expanded = false
                        onSelect(preset.id.takeUnless { checked })
                    },
                    modifier = Modifier.semantics { selected = checked },
                    trailingIcon = {
                        if (checked) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = colors.primary)
                    },
                )
            }
        }
    }
}

@Composable
private fun MingVoiceSearch(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.ming_preset_search)
    val focus = LocalFocusManager.current
    val style = MaterialTheme.typography.bodyLarge
    BasicTextField(
        value, onChange, modifier.fillMaxWidth().semantics { contentDescription = label },
        singleLine = true, textStyle = style.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
                .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(label, style = style, color = colors.onSurfaceVariant)
                    input()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.ming_preset_clear_search), Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant)
                }
            }
        },
    )
}

private fun voiceName(id: String, language: String) =
    if (language == "zh") id.substringAfterLast('_').substringBefore(" (") else id.replace('_', ' ')

internal fun selectionName(resources: Resources, preset: SynthesisVoicePreset): String {
    val base = preset.baseId ?: return voiceName(preset.id, preset.language)
    return "${voiceName(base, preset.language)} · ${emotionName(resources, preset)}"
}

private fun emotionName(resources: Resources, variant: SynthesisVoicePreset): String {
    val suffix = variant.id.removePrefix("${variant.baseId}_")
    val label = when (suffix) {
        "中性" -> R.string.ming_emotion_neutral
        "高兴" -> R.string.ming_emotion_happy
        "悲伤" -> R.string.ming_emotion_sad
        "生气" -> R.string.ming_emotion_angry
        "恐惧" -> R.string.ming_emotion_fearful
        "厌恶" -> R.string.ming_emotion_disgusted
        "惊讶" -> R.string.ming_emotion_surprised
        else -> return suffix
    }
    return resources.getString(label)
}
