package dev.renakoni.voicebox.studio.creation.gptsovits

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.studio.creation.AdvancedSettingsPanel
import dev.renakoni.voicebox.studio.creation.CreationModel
import dev.renakoni.voicebox.studio.creation.CreationSettings
import dev.renakoni.voicebox.studio.creation.NumericSynthesisSettings
import dev.renakoni.voicebox.studio.creation.SegmentedChoice
import dev.renakoni.voicebox.studio.creation.SheetSection
import dev.renakoni.voicebox.studio.creation.numericOverrides
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
        val controls = settings.optionControls
        val tuned = numericOverrides(controls, settings.options)
        val summary = listOfNotNull(
            languageName(resources, language).takeIf { manual },
            resources.getString(R.string.settings_adjusted, tuned).takeIf { tuned > 0 },
        ).joinToString(" · ").ifEmpty { null }
        AdvancedSettingsPanel(summary, enabled, onReset = settings::resetOptionsAndLanguage) {
            SheetSection(stringResource(R.string.output_language)) {
                val choices = listOf(automaticOutputLanguage to stringResource(R.string.output_language_auto)) +
                    languages.map { it to languageName(resources, it) }
                SegmentedChoice(choices, language, onSelect = settings::changeLanguage)
            }
            if (controls.isNotEmpty()) SheetSection(stringResource(R.string.settings_synthesis)) {
                NumericSynthesisSettings(controls, settings.options, settings::changeOptions)
            }
        }
    }
}
