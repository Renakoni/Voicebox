package dev.renakoni.voicebox.studio.creation.ming

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import dev.renakoni.voicebox.R
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
    override fun VoiceExtras(settings: CreationSettings, enabled: Boolean) {
        if (settings.presets.isNotEmpty()) {
            MingVoicePresetSetting(settings.presets, settings.preset, enabled, settings::changePreset)
        }
    }

    @Composable
    override fun Panels(settings: CreationSettings, enabled: Boolean) {
        val options = settings.options
        val language = settings.outputLanguage
        // "use_reference" only qualifies the instruction, so it is not a separate adjustment.
        val changed = options.keys.count { it != "use_reference" } + (if (language.isNotEmpty()) 1 else 0)
        val summary = if (changed > 0) LocalResources.current.getString(R.string.settings_adjusted, changed) else null
        AdvancedSettingsPanel(summary, enabled, onReset = settings::resetOptionsAndLanguage) {
            MingSynthesisSettings(
                options = options, presetActive = settings.preset != null,
                outputLanguage = language,
                languages = settings.languages.takeIf { settings.usesOutputLanguage }.orEmpty(),
                onLanguageChange = settings::changeLanguage, onChange = settings::changeOptions,
            )
        }
    }

    override fun editableOptions(saved: Map<String, String>, preset: SynthesisVoicePreset?) =
        if (preset == null) saved else saved.filterKeys { it == "seed" }

    override fun requestOptions(editable: Map<String, String>, preset: SynthesisVoicePreset?) =
        if (preset == null) editable else editable + ("ip" to preset.id)

    override fun saveOptions(saved: Map<String, String>, edited: Map<String, String>, preset: SynthesisVoicePreset?) =
        if (preset == null) edited else (saved - "seed") + edited.filterKeys { it == "seed" }
}
