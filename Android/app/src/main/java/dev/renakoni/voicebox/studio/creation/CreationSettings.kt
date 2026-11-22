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
    private val automaticLanguage get() = provider?.requiresOutputLanguage == false
    val outputLanguage: String get() {
        val ref = selectedModel() ?: return ""
        return languagesByBackend[ref.backendId]?.takeIf { it in languages }
            ?: if (automaticLanguage) "" else languages.firstOrNull().orEmpty()
    }
    val usesOutputLanguage get() = provider?.usesOutputLanguage(options) ?: true
    val sampleLanguage get() = outputLanguage.ifBlank { preset?.language.orEmpty() }
    val optionControls get() = provider?.controls.orEmpty()
    val presets get() = presetsByModel[selectedModel()].orEmpty()
    val preset get() = presets.firstOrNull { it.id == presetSelections[selectedModel()] }
    val options get() = model.editableOptions(optionsByBackend[selectedModel()?.backendId].orEmpty(), preset)
    val supportsStreaming get() = provider?.supportsStreaming == true
    val streaming get() = supportsStreaming && preferStreaming

    fun requestOptions() = provider?.studioOptions(model.requestOptions(options, preset)).orEmpty()

    fun updatePresets(presets: Map<ModelRef, List<SynthesisVoicePreset>>) {
        presetsByModel = presets
        presetSelections = presets.keys.associateWith(store::voicePreset)
    }

    fun changePreset(id: String?) {
        val ref = selectedModel() ?: return
        if (!canEdit() || id != null && presets.none { it.id == id }) return
        savePreset(ref, id)
        onChange(true)
    }

    fun selectReference(ref: ModelRef) = savePreset(ref, null)

    private fun savePreset(ref: ModelRef, id: String?) {
        if (presetSelections[ref] == id) return
        store.saveVoicePreset(ref, id)
        presetSelections = presetSelections + (ref to id)
    }

    fun changeLanguage(language: String) {
        val ref = selectedModel() ?: return
        if (!canEdit() || language !in languages && !(language.isEmpty() && automaticLanguage)) return
        saveLanguage(ref.backendId, language)
        onChange(true)
    }

    private fun saveLanguage(backendId: String, language: String) {
        store.saveLanguage(backendId, language)
        languagesByBackend = languagesByBackend + (backendId to language)
    }

    fun changeOptions(value: Map<String, String>) {
        if (!canEdit()) return
        saveOptions(value)
        onChange(false)
    }

    fun resetOptionsAndLanguage() {
        val ref = selectedModel() ?: return
        if (!canEdit() || !automaticLanguage) return
        saveLanguage(ref.backendId, "")
        saveOptions(emptyMap())
        onChange(true)
    }

    private fun saveOptions(value: Map<String, String>) {
        val backend = provider ?: return
        val editable = if (backend.synthesisSettingsLayout != null) value else {
            val keys = backend.controls.map { it.name }.toSet()
            value.filterKeys { it in keys }
        }
        val saved = model.saveOptions(optionsByBackend[backend.id].orEmpty(), editable, preset)
        store.saveOptions(backend.id, saved)
        optionsByBackend = optionsByBackend + (backend.id to saved)
    }

    fun changeStreaming(enabled: Boolean) {
        if (canEdit() && supportsStreaming) preferStreaming = enabled
    }
}
