package dev.renakoni.voicebox.bridge

enum class SynthesisSettingsLayout { MING }

interface SynthesisSettingsProvider {
    val synthesisSettingsLayout: SynthesisSettingsLayout
    val requiresOutputLanguage: Boolean get() = true

    fun usesReference(options: Map<String, String>): Boolean = true

    fun usesOutputLanguage(options: Map<String, String>): Boolean = true

    fun mergeSynthesisOptions(defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String> =
        defaults + overrides
}

/** Publishes ordered mono 44.1 kHz PCM16 fragments to GenerationContext.streamDirectory. */
interface StreamingSynthesisProvider
