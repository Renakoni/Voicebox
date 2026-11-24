package dev.renakoni.voicebox.bridge

enum class SynthesisSettingsLayout { MING, QWEN }

enum class ReferenceInput { REQUIRED, OPTIONAL, NONE }

interface SynthesisSettingsProvider {
    val synthesisSettingsLayout: SynthesisSettingsLayout
    val requiresOutputLanguage: Boolean get() = true
    fun referenceInput(model: CharacterModel): ReferenceInput = ReferenceInput.REQUIRED

    fun usesReference(options: Map<String, String>): Boolean = true

    fun usesOutputLanguage(options: Map<String, String>): Boolean = true

    fun mergeSynthesisOptions(defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String> =
        defaults + overrides
}

/** Publishes ordered mono PCM16 fragments to GenerationContext.streamDirectory. */
interface StreamingSynthesisProvider {
    val streamSampleRate: Int get() = 44100
}
