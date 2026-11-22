package dev.renakoni.voicebox.studio.creation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import dev.renakoni.voicebox.core.BackendRegistry
import dev.renakoni.voicebox.studio.StudioStore

/** Session choices and persisted settings for the currently selected model. */
internal class CreationSettings(
    private val store: StudioStore,
    private val backends: BackendRegistry,
    private val selectedModel: () -> ModelRef?,
    private val languagesFor: (ModelRef) -> List<String>,
    private val canEdit: () -> Boolean,
    private val onChange: (clearError: Boolean) -> Unit,
) {
    private val provider get() = selectedModel()?.let { backends.provider(it.backendId) }
    val model get() = creationModel(provider)

    private var languagesByBackend by mutableStateOf(backends.providers.associate { it.id to store.outputLanguage(it.id) })
    private var optionsByBackend by mutableStateOf(backends.providers.associate { it.id to store.options(it.id) })
    private var presetsByModel by mutableStateOf(emptyMap<ModelRef, List<SynthesisVoicePreset>>())
    private var presetSelections by mutableStateOf(emptyMap<ModelRef, String?>())
    private var preferStreaming by mutableStateOf(false)

    val hasModel get() = selectedModel() != null
    val languages get() = selectedModel()?.let(languagesFor).orEmpty()
    private val automaticLanguage get() = model.automaticOutputLanguage
        ?: "".takeIf { provider?.requiresOutputLanguage == false }
    val outputLanguage: String get() {
        val ref = selectedModel() ?: return ""
        val automatic = automaticLanguage
        return languagesByBackend[ref.backendId]?.takeIf { it == automatic || it in languages }
            ?: automatic ?: languages.firstOrNull().orEmpty()
    }
    val usesOutputLanguage get() = provider?.usesOutputLanguage(options) ?: true
    val sampleLanguage get() = if (usesOutputLanguage) outputLanguage.ifBlank { preset?.language.orEmpty() } else ""
    val optionControls get() = provider?.controls.orEmpty()
    val presets get() = presetsFor(selectedModel())
    fun presetsFor(ref: ModelRef?) = presetsByModel[ref].orEmpty()
    val preset get() = presets.firstOrNull { it.id == presetSelections[selectedModel()] }
    val options get() = model.editableOptions(optionsByBackend[selectedModel()?.backendId].orEmpty(), preset)
    val supportsStreaming get() = provider?.supportsStreaming == true
    val streaming get() = supportsStreaming && preferStreaming

    fun requestOptions() = provider?.studioOptions(optionValues()).orEmpty()

    fun defaultOptions(): Map<String, String> {
        val backend = provider ?: return emptyMap()
        val values = optionValues()
        // UI defaults from model-specific controls are validated when the API submits a request.
        return if (backend.synthesisSettingsLayout != null) values else backend.studioOptions(values)
    }

    private fun optionValues() = model.requestOptions(options, preset)

    fun updatePresets(presets: Map<ModelRef, List<SynthesisVoicePreset>>) {
        presetsByModel = presets
        presetSelections = presets.keys.associateWith(store::voicePreset)
    }

    fun changePreset(id: String?) {
        val ref = selectedModel() ?: return
        if (!canEdit() || id != null && presets.none { it.id == id }) return
        if (savePreset(ref, id)) onChange(true)
    }

    fun selectReference(ref: ModelRef) { savePreset(ref, null) }

    private fun savePreset(ref: ModelRef, id: String?): Boolean {
        if (presetSelections[ref] == id) return false
        store.saveVoicePreset(ref, id)
        presetSelections = presetSelections + (ref to id)
        return true
    }

    fun changeLanguage(language: String) {
        val ref = selectedModel() ?: return
        if (!canEdit() || language == outputLanguage ||
            language !in languages && language != automaticLanguage) return
        saveLanguage(ref.backendId, language)
        onChange(true)
    }

    private fun saveLanguage(backendId: String, language: String) {
        store.saveLanguage(backendId, language)
        languagesByBackend = languagesByBackend + (backendId to language)
    }

    fun changeOptions(value: Map<String, String>) {
        if (!canEdit()) return
        if (saveOptions(value)) onChange(false)
    }

    fun resetOptionsAndLanguage() {
        val ref = selectedModel() ?: return
        val automatic = automaticLanguage ?: return
        if (!canEdit()) return
        if (outputLanguage != automatic) saveLanguage(ref.backendId, automatic)
        saveOptions(emptyMap())
        onChange(true)
    }

    private fun saveOptions(value: Map<String, String>): Boolean {
        val backend = provider ?: return false
        val previous = optionsByBackend[backend.id].orEmpty()
        val saved = model.saveOptions(previous, value, preset)
        if (saved == previous) return false
        store.saveOptions(backend.id, saved)
        optionsByBackend = optionsByBackend + (backend.id to saved)
        return true
    }

    fun changeStreaming(enabled: Boolean) {
        if (canEdit() && supportsStreaming) preferStreaming = enabled
    }
}
