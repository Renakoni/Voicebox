@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.renakoni.voicebox.studio

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.LanguageTags
import dev.renakoni.voicebox.core.ReferenceLanguage

internal fun languageName(resources: Resources, value: String) = when (LanguageTags.normalize(value)) {
    "japanese" -> resources.getString(R.string.language_japanese)
    "english" -> resources.getString(R.string.language_english)
    "chinese" -> resources.getString(R.string.language_chinese)
    "auto" -> resources.getString(R.string.language_auto_detect)
    else -> value
}

@Composable
internal fun LanguageChoice(title: String, value: String, enabled: Boolean,
    languages: List<String>, autoText: String? = null, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LanguageMenu(title, value, enabled, languages, autoText, onSelect = onSelect)
    }
}

@Composable
internal fun LanguageMenu(title: String, value: String, enabled: Boolean,
    languages: List<String>, autoText: String? = null, onSelect: (String) -> Unit) {
    val resources = LocalResources.current
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        value == "auto" && autoText != null -> resources.getString(R.string.language_auto,
            languageName(resources, ReferenceLanguage.resolve(value, autoText)))
        value.isBlank() -> title
        else -> languageName(resources, value)
    }
    Box {
        StudioButton(onClick = { expanded = true }, enabled = enabled, style = StudioButtonStyle.Quiet,
            modifier = Modifier.semantics { contentDescription = resources.getString(R.string.language_selection_description, title, label) }) {
            Text(label)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (autoText != null) DropdownMenuItem(text = { Text(resources.getString(R.string.language_from_transcript)) },
                onClick = { onSelect("auto"); expanded = false })
            languages.forEach { language ->
                DropdownMenuItem(text = { Text(languageName(resources, language)) },
                    onClick = { onSelect(language); expanded = false })
            }
        }
    }
}
