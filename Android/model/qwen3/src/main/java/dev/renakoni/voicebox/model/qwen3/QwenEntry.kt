package dev.renakoni.voicebox.model.qwen3

import android.content.Context
import android.net.Uri
import android.os.Bundle
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.LanguageTags
import dev.renakoni.voicebox.model.qwen3.checkpoint.QwenCheckpointVoices
import java.io.File

class QwenEntry : BackendEntry {
    override fun open(context: Context, directory: File, work: File, settings: Bundle): BackendProvider =
        QwenProvider(context, directory, work, settings.getBoolean("allowNpu"))
}

private val languages = mapOf("auto" to "auto", "zh" to "chinese", "en" to "english", "ja" to "japanese",
    "ko" to "korean", "de" to "german", "fr" to "french", "ru" to "russian", "pt" to "portuguese",
    "es" to "spanish", "it" to "italian")

internal fun qwenLanguage(tag: String): String? {
    val normalized = LanguageTags.normalize(tag).ifBlank { "auto" }
    return languages[normalized.substringBefore('-')] ?: normalized.takeIf { it in languages.values }
}

private class QwenProvider(private val context: Context, private val directory: File, private val work: File,
    private val allowNpu: Boolean) : BackendProvider, VoiceLibrary, StreamingSynthesisProvider,
    SynthesisSettingsProvider {
    override val id = "qwen3"
    private val model = CharacterModel(ModelRef(id, "Q4_K_M", "qwen3-tts-0.6b-base"),
        "Qwen3-TTS-0.6B Q4", "qwen3-model")
    private val resources = QwenResources(context)
    private val imported = QwenCheckpointVoices(context, directory)
    private val devices by lazy { QwenDevices.available(context, directory) }
    override val capabilities = BackendCapabilities(languages.keys.toList(), setOf(VoiceImport.QWEN_SAFETENSORS))
    override val supportsWarmup = false
    override val requiresOutputLanguage = false
    override fun languageError(language: String, text: String) = if (qwenLanguage(language) == null) "qwen_language_unsupported" else null
    override val synthesisSettingsLayout = SynthesisSettingsLayout.QWEN
    override val streamSampleRate = 24000
    override fun discoverCharacterModels() = listOf(model) + imported.discover().map { it.model }
    override fun inspect(model: CharacterModel): ModelInspectionResult {
        val voice = if (model.ref == this.model.ref) null else imported.find(model)
        return ModelInspectionResult(listOf(resources.codec, voice?.weights ?: resources.talker)
            .filterNot(File::isFile).map(File::getName))
    }
    override fun referenceInput(model: CharacterModel) =
        if (model.ref == this.model.ref || imported.find(model).speaker == null) ReferenceInput.OPTIONAL else ReferenceInput.NONE
    override fun findReferenceExample(model: CharacterModel): ReferenceExample? = null
    override fun supportsAuxiliaryReferences(model: CharacterModel) = false
    override fun executionModes() = if (devices and 1 != 0) ExecutionBackend.entries.toSet() else setOf(ExecutionBackend.CPU)
    override fun accelerationInfo() = AccelerationInfo(available = devices and 2 != 0)
    override fun createRuntime(execution: ExecutionBackend) =
        QwenRuntime(context, directory, work, resources, model.ref, imported, execution, allowNpu)
    override fun validateOptions(options: Map<String, String>): Map<String, String> {
        QwenSynthesisOptions(options)
        return options
    }

    override fun resourcePacks(cancel: () -> Unit) = emptyList<ResourcePack>()
    override fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext,
        progress: (String, Int?) -> Unit): Bundle {
        require(action == LibraryAction.IMPORT_QWEN_CHECKPOINT) { "Unsupported Qwen library action: $action" }
        val voice = imported.import(requireNotNull(arguments.getString("name")),
            Uri.parse(requireNotNull(arguments.getString("config"))), Uri.parse(requireNotNull(arguments.getString("weights"))),
            context::checkCancelled) { percent -> progress("Qwen Q4 · $percent%", percent) }
        return Bundle().apply {
            putString("id", voice.id)
            putString("variant", voice.ref.variant)
            putString("name", voice.displayName)
        }
    }
    override fun recoverInstallations() = imported.recoverInterrupted()
    override fun officialVoices() = listOf(OfficialVoice(model.ref, model.displayName, ""))
    override fun variantLabel(variant: String) = "qwen_q4"
    override fun resourceHint(variant: String): String? = null
    override val controls = QwenSynthesisOptions.controls
    override fun studioOptions(values: Map<String, String>) = values
}
