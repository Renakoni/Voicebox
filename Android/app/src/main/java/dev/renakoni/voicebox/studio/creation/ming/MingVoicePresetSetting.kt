package dev.renakoni.voicebox.studio.creation.ming

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.studio.StudioControlShape
import dev.renakoni.voicebox.studio.creation.StudioSettingsHeader
import dev.renakoni.voicebox.studio.languageName

@Composable
internal fun MingVoicePresetSetting(presets: List<SynthesisVoicePreset>, active: SynthesisVoicePreset?, enabled: Boolean,
    onSelect: (String?) -> Unit) {
    var browsing by rememberSaveable(presets) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val resources = LocalResources.current
    StudioSettingsHeader(
        title = stringResource(R.string.ming_official_voices),
        expanded = browsing && enabled,
        summary = active?.let { selectionName(resources, it) },
        enabled = enabled,
    ) {
        focus.clearFocus()
        browsing = true
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
    var searching by rememberSaveable { mutableStateOf(false) }
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
    val goBack: () -> Unit = {
        if (searching) {
            searching = false
            search = ""
        } else onBack()
    }
    Dialog(
        onDismissRequest = goBack,
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
        val searchFocus = remember { FocusRequester() }
        LaunchedEffect(searching) {
            if (searching) {
                searchFocus.requestFocus()
                keyboard?.show()
            }
        }
        Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        focus.clearFocus()
                        keyboard?.hide()
                        goBack()
                    }) {
                        val label = if (searching) R.string.ming_preset_end_search else R.string.back_create
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(label))
                    }
                    if (searching) {
                        MingVoiceSearch(search, { search = it }, Modifier.weight(1f).focusRequester(searchFocus))
                        if (search.isNotEmpty()) IconButton(onClick = {
                            search = ""
                            searchFocus.requestFocus()
                            keyboard?.show()
                        }) {
                            Icon(Icons.Default.Close, stringResource(R.string.ming_preset_clear_search), Modifier.size(20.dp))
                        }
                    } else {
                        Text(
                            stringResource(R.string.ming_official_voices),
                            Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                        )
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, stringResource(R.string.ming_preset_search), Modifier.size(22.dp))
                        }
                    }
                }
                if (languages.size > 1) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).selectableGroup()) {
                        for (tab in listOf(null) + languages) {
                            val selected = tab == language
                            val title = if (tab == null) stringResource(R.string.ming_preset_all) else languageName(resources, tab)
                            Box(Modifier.weight(1f, fill = false).selectable(selected, role = Role.Tab, onClick = { language = tab })
                                .widthIn(min = 48.dp).heightIn(min = 48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                Text(title, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant.copy(alpha = .65f))
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (results.isEmpty()) item {
                        Text(stringResource(R.string.ming_preset_no_results), Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    items(results, key = { it.id }) { voice ->
                        val checked = activeBase == voice.id
                        val variants = groups[voice.id].orEmpty()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PresetChoice(
                                title = if (checked && active != null) selectionName(resources, active) else voiceName(voice.id, voice.language),
                                detail = descriptions[voice.id],
                                checked = checked,
                                modifier = Modifier.weight(1f),
                            ) { onSelect(if (checked) null else voice.id) }
                            if (variants.isNotEmpty()) {
                                MingPresetEmotionMenu(voice, variants, active?.id, onSelect)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MingPresetEmotionMenu(voice: SynthesisVoicePreset, variants: List<SynthesisVoicePreset>, selectedId: String?,
    onSelect: (String?) -> Unit) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(false) }
    val action = stringResource(R.string.ming_preset_voice_emotions, voiceName(voice.id, voice.language))
    val state = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    Box {
        Row(Modifier.clickable(role = Role.Button) {
            focus.clearFocus()
            keyboard?.hide()
            expanded = true
        }.semantics {
            contentDescription = action
            stateDescription = state
        }.heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ming_emotion), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
                Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 136.dp).heightIn(max = 408.dp),
            shape = StudioControlShape,
            containerColor = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .7f)),
        ) {
            for ((index, preset) in (listOf(voice) + variants).withIndex()) {
                val checked = selectedId == preset.id
                val label = if (preset.baseId == null) stringResource(R.string.ming_preset_original) else emotionName(resources, preset)
                if (index == 1) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = colors.outlineVariant.copy(alpha = .7f))
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
private fun PresetChoice(title: String, checked: Boolean, modifier: Modifier = Modifier, detail: String? = null,
    onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { onSelect() })
            .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (checked) colors.primary else colors.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (checked) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = colors.primary)
    }
}

@Composable
private fun MingVoiceSearch(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.ming_preset_search)
    val focus = LocalFocusManager.current
    val style = MaterialTheme.typography.bodyMedium
    BasicTextField(
        value, onChange, modifier.semantics { contentDescription = label },
        singleLine = true, textStyle = style.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { input ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(label, style = style, color = colors.onSurfaceVariant)
                input()
            }
        },
    )
}

private fun voiceName(id: String, language: String) =
    if (language == "zh") id.substringAfterLast('_').substringBefore(" (") else id.replace('_', ' ')

private fun selectionName(resources: Resources, preset: SynthesisVoicePreset): String {
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
