package dev.renakoni.voicebox.studio.creation.gptsovits

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.studio.LanguageChoice
import dev.renakoni.voicebox.studio.creation.AdvancedSettingsPanel
import dev.renakoni.voicebox.studio.creation.CreationModel
import dev.renakoni.voicebox.studio.creation.CreationSettings
import dev.renakoni.voicebox.studio.creation.NumericSynthesisSettings
import dev.renakoni.voicebox.studio.languageName

internal object GptSovitsCreation : CreationModel {
    override val automaticOutputLanguage = "auto"

    @Composable
    override fun Panels(settings: CreationSettings, enabled: Boolean) {
        val languages = settings.languages
        if (languages.isEmpty()) return
        val language = settings.outputLanguage
        val resources = LocalResources.current
        val manual = language != automaticOutputLanguage
        val summary = languageName(resources, language).takeIf { manual }
        AdvancedSettingsPanel(summary) { expanded ->
            LanguageChoice(stringResource(R.string.output_language), language, enabled,
                listOf(automaticOutputLanguage) + languages, onSelect = settings::changeLanguage)
            NumericSynthesisSettings(settings.optionControls, settings.options, enabled, expanded,
                settings::changeOptions, hasOtherOverrides = manual, onReset = settings::resetOptionsAndLanguage)
        }
    }
}
