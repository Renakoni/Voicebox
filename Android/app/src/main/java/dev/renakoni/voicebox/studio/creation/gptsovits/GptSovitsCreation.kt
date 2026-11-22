package dev.renakoni.voicebox.studio.creation.gptsovits

import androidx.compose.runtime.Composable
import dev.renakoni.voicebox.studio.creation.CreationModel
import dev.renakoni.voicebox.studio.creation.CreationSettings
import dev.renakoni.voicebox.studio.creation.NumericSynthesisSettings
import dev.renakoni.voicebox.studio.creation.OutputLanguageToolbarMenu

internal object GptSovitsCreation : CreationModel {
    @Composable
    override fun Toolbar(settings: CreationSettings, enabled: Boolean) {
        OutputLanguageToolbarMenu(settings, enabled)
    }

    @Composable
    override fun Panels(settings: CreationSettings, enabled: Boolean) {
        NumericSynthesisSettings(settings.optionControls, settings.options, enabled, settings::changeOptions)
    }
}
