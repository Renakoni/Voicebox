package dev.renakoni.voicebox.studio.creation

import androidx.compose.runtime.Composable
import dev.renakoni.voicebox.bridge.SynthesisSettingsLayout
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.studio.creation.gptsovits.GptSovitsCreation
import dev.renakoni.voicebox.studio.creation.ming.MingCreation

internal interface CreationModel {
    val automaticOutputLanguage: String? get() = null

    @Composable
    fun Toolbar(settings: CreationSettings, enabled: Boolean) = Unit

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
        AdvancedSettingsPanel { expanded ->
            NumericSynthesisSettings(controls, settings.options, enabled, expanded, settings::changeOptions)
        }
    }
}
