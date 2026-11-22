package dev.renakoni.voicebox.bridge

data class SynthesisVoicePreset(
    val id: String,
    val language: String,
    val baseId: String? = null,
    val recommended: Boolean = false,
)

/** Inference voice IDs supplied by the installed model package. */
interface VoicePresetProvider {
    fun voicePresets(model: CharacterModel): List<SynthesisVoicePreset>
}
