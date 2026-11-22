package dev.renakoni.voicebox.studio.creation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.SynthesisSettingsLayout
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.studio.creation.gptsovits.GptSovitsCreation
import dev.renakoni.voicebox.studio.creation.ming.MingCreation

internal interface CreationModel {
    val automaticOutputLanguage: String? get() = null

    /** Compact controls beside the generate button, e.g. a generation-mode switch. */
    @Composable
    fun Toolbar(settings: CreationSettings, enabled: Boolean) = Unit

    /** Extra rows rendered inside the voice card, below the selected voice. */
    @Composable
    fun VoiceExtras(settings: CreationSettings, enabled: Boolean) = Unit

    /** Settings entry points rendered below the writing surface. */
    @Composable
    fun Panels(settings: CreationSettings, enabled: Boolean)

    fun editableOptions(saved: Map<String, String>, preset: SynthesisVoicePreset?) = saved

    fun requestOptions(editable: Map<String, String>, preset: SynthesisVoicePreset?) = editable

    fun saveOptions(saved: Map<String, String>, edited: Map<String, String>, preset: SynthesisVoicePreset?) = edited
}

internal fun creationModel(provider: InstalledBackend?): CreationModel = when {
    provider?.synthesisSettingsLayout == SynthesisSettingsLayout.MING -> MingCreation
    provider?.id == "gptsovits" -> GptSovitsCreation
    else -> DefaultCreation
}

private object DefaultCreation : CreationModel {
    @Composable
    override fun Toolbar(settings: CreationSettings, enabled: Boolean) {
        if (settings.supportsStreaming) StreamingToolbarMenu(settings, enabled)
        else OutputLanguageToolbarMenu(settings, enabled)
    }

    @Composable
    override fun Panels(settings: CreationSettings, enabled: Boolean) {
        val controls = settings.optionControls
        if (controls.isEmpty()) return
        val changed = numericOverrides(controls, settings.options)
        val summary = if (changed > 0) LocalResources.current.getString(R.string.settings_adjusted, changed) else null
        AdvancedSettingsPanel(summary, enabled, onReset = { settings.changeOptions(emptyMap()) }) {
            SheetSection(LocalResources.current.getString(R.string.settings_synthesis)) {
                NumericSynthesisSettings(controls, settings.options, settings::changeOptions)
            }
        }
    }
}
