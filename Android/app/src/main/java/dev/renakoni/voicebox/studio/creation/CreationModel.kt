package dev.renakoni.voicebox.studio.creation

import androidx.compose.runtime.Composable
import dev.renakoni.voicebox.bridge.SynthesisSettingsLayout
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.studio.creation.gptsovits.GptSovitsCreation
import dev.renakoni.voicebox.studio.creation.ming.MingCreation

internal interface CreationModel {
    @Composable
    fun Toolbar(settings: CreationSettings, enabled: Boolean)

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
        NumericSynthesisSettings(settings.optionControls, settings.options, enabled, settings::changeOptions)
    }
}
