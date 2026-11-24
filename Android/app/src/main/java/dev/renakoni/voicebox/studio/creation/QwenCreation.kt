package dev.renakoni.voicebox.studio.creation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.bridge.OptionControl
import dev.renakoni.voicebox.studio.SettingsSwitch

internal object QwenCreation : CreationModel by DefaultCreation {
    @Composable
    override fun Panels(settings: CreationSettings, enabled: Boolean) {
        val options = settings.options
        val controls = settings.optionControls
        val changed = numericOverrides(controls, options) +
            listOf("do_sample", "subtalker_dosample").count { options[it] == "false" }
        val summary = if (changed > 0) stringResource(R.string.settings_adjusted, changed) else null
        AdvancedSettingsPanel(summary, enabled, onReset = { settings.changeOptions(emptyMap()) }) {
            SheetSection(stringResource(R.string.qwen_talker)) {
                SamplingSettings("do_sample", controls.filter { it.name in setOf("temperature", "top_k", "top_p") }, settings)
                NumericSynthesisSettings(controls.filter { it.name == "repetition_penalty" }, options, settings::changeOptions)
            }
            SheetSection(stringResource(R.string.qwen_subtalker)) {
                SamplingSettings("subtalker_dosample", controls.filter { it.name.startsWith("subtalker_") }, settings)
            }
            SheetSection(stringResource(R.string.qwen_length), stringResource(R.string.qwen_context_limit)) {
                NumericSynthesisSettings(controls.filter { it.name == "max_new_tokens" }, options, settings::changeOptions)
            }
        }
    }

    @Composable
    private fun SamplingSettings(key: String, controls: List<OptionControl>, settings: CreationSettings) {
        val options = settings.options
        val sampling = options[key] != "false"
        SettingsSwitch(stringResource(R.string.qwen_sampling), sampling,
            onChange = { settings.changeOptions(options + (key to it.toString())) }, horizontalPadding = 0.dp)
        if (sampling) NumericSynthesisSettings(controls, options, settings::changeOptions)
    }
}
