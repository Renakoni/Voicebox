package dev.renakoni.voicebox.model.gptsovits

import android.content.Context
import android.net.Uri
import android.os.Bundle
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.openBackendAsset
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.errorUi
import dev.renakoni.voicebox.model.gptsovits.checkpoint.*
import java.io.File

class GptEntry : BackendEntry {
    override fun open(context: Context, directory: File, work: File, settings: Bundle): BackendProvider {
        val device = DeviceAcceleration(context)
        return GptProvider(context, device, { settings.getInt("t2sThreads") }, { settings.getInt("vocoderThreads") },
            { settings.getBoolean("allowNpu", true) }, settings.getBoolean("webGpuVerified"))
    }
}

internal class GptLibrary(private val context: Context, private val device: DeviceAcceleration) : VoiceLibrary {
    private val root = requireNotNull(context.getExternalFilesDir(null))
    private val catalog by lazy { OfficialModelCatalog(context) }
    override fun officialVoices() = catalog.models.map { OfficialVoice(it.ref, it.name, variantLabel(it.architecture)) }
    override fun variantLabel(variant: String) = requireNotNull(modelArchitectureText(variant).key)
    override fun resourcesReady(variant: String) = catalog.runtimeReady(root, variant)
    override fun resourceHint(variant: String) = if (variant == "v5turbo") "engine_v5_resources" else "engine_resources"
    override val controls = listOf(
        OptionControl("speed", "speech_speed", "1.0", 0.6f, 1.65f, 21, unit = "×"),
        OptionControl("temperature", "temperature", "1.0", 0f, 1f, 100),
        OptionControl("topK", "top_k", "15", 1f, 100f, 99, integral = true),
        OptionControl("topP", "top_p", "1.0", 0f, 1f, 100),
        OptionControl("repetitionPenalty", "repetition_penalty", "1.35", 1f, 2f, 100),
    )
    override fun studioOptions(values: Map<String, String>) = SynthesisOptions.parse(values).toRequestOptions(1500)
    override fun hasNpuGraphs(model: CharacterModel): Boolean {
        val directory = File(root, "CharacterModels/${model.relativeModelDirectory}")
        return if (model.ref.variant == "v5turbo") V5DiTRuntime.hasHtpGraphs(directory)
        else V2HtpVocoder.hasGraphs(directory)
    }
    override fun accelerationInfo() = device.status.let { AccelerationInfo(it.device, it.available, requireNotNull(it.detail.key)) }

    override fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext, progress: (String, Int?) -> Unit): Bundle {
        val app = this.context
        fun value(key: String) = requireNotNull(arguments.getString(key))
        fun open(key: String) = app.contentResolver.openInputStream(Uri.parse(value(key))) ?: errorUi(UiText("resource_open_failed"))
        fun report(label: UiText) = progress(label.resolve(app.resources), null)
        val result = Bundle()
        when (action) {
            LibraryAction.PREVIEW_PAIR -> result.putString("variant", CheckpointPreview.read(app, Uri.parse(value("uri"))))
            LibraryAction.DOWNLOAD_VOICE -> OfficialModelInstaller(root, catalog, app::openBackendAsset)
                .install(catalog.models.single { it.id == value("model") }, context::checkCancelled) {
                    it.label?.let { label -> progress(label.resolve(app.resources), it.percent) }
                }
            LibraryAction.IMPORT_ARCHIVE -> open("uri").use { ResourcePackageImporter(root).importRuntime(it, context::checkCancelled) }
            LibraryAction.CLEAR_CACHE -> result.putInt("derived", RuntimeAssetRepository(root).clearDerivedFiles())
            LibraryAction.EDIT_REFERENCE, LibraryAction.IMPORT_PAIR -> {
                val reference = arguments.getString("reference")?.let {
                    CheckpointImporter.Reference(File(it), value("transcript"), value("language"))
                }
                val name = value("name")
                val imported = if (action == LibraryAction.EDIT_REFERENCE) {
                    CharacterVoiceEditor(root).updateReference(value("id"), name, requireNotNull(reference), value("variant"))
                    CheckpointImporter.Result(value("id"), name.trim(), value("variant"))
                } else CheckpointImporter(root, app::openBackendAsset).import(name,
                    openGpt = { open("first") }, openSovits = { open("second") }, progress = ::report,
                    checkCancelled = context::checkCancelled, reference = reference, replaceCharacterId = arguments.getString("id"),
                    gptFilename = arguments.getString("firstName").orEmpty(), sovitsFilename = arguments.getString("secondName").orEmpty(),
                    architectureHint = arguments.getString("variant"))
                result.putString("id", imported.characterId); result.putString("name", imported.displayName)
                result.putString("variant", imported.architecture)
            }
        }
        return result
    }
}
