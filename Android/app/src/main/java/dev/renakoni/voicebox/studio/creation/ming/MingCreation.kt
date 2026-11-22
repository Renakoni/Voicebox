package dev.renakoni.voicebox.studio.creation.ming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.studio.creation.AdvancedSettingsPanel
import dev.renakoni.voicebox.studio.creation.CreationModel
import dev.renakoni.voicebox.studio.creation.CreationSettings
import dev.renakoni.voicebox.studio.creation.StreamingToolbarMenu

internal object MingCreation : CreationModel {
    @Composable
    override fun Toolbar(settings: CreationSettings, enabled: Boolean) {
        StreamingToolbarMenu(settings, enabled)
    }

    @Composable
    override fun Panels(settings: CreationSettings, enabled: Boolean) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
            if (settings.presets.isNotEmpty()) {
                MingVoicePresetSetting(settings.presets, settings.preset, enabled, settings::changePreset)
            }
            AdvancedSettingsPanel {
                MingSynthesisSettings(
                    options = settings.options, enabled = enabled, presetActive = settings.preset != null,
                    outputLanguage = settings.outputLanguage,
                    languages = settings.languages.takeIf { settings.usesOutputLanguage }.orEmpty(),
                    onLanguageChange = settings::changeLanguage, onChange = settings::changeOptions,
                    onReset = settings::resetOptionsAndLanguage,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    override fun editableOptions(saved: Map<String, String>, preset: SynthesisVoicePreset?) =
        if (preset == null) saved else saved.filterKeys { it == "seed" }

    override fun requestOptions(editable: Map<String, String>, preset: SynthesisVoicePreset?) =
        if (preset == null) editable else editable + ("ip" to preset.id)

    override fun saveOptions(saved: Map<String, String>, edited: Map<String, String>, preset: SynthesisVoicePreset?) =
        if (preset == null) edited else (saved - "seed") + edited.filterKeys { it == "seed" }
}
