package dev.renakoni.voicebox.model.gptsovits

import android.content.Context
import android.net.Uri
import android.os.Bundle
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.openBackendAsset
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import dev.renakoni.voicebox.core.i18n.errorUi
import dev.renakoni.voicebox.model.gptsovits.checkpoint.*
import java.io.File

class GptEntry : BackendEntry {
    override fun open(context: Context, directory: File, work: File, settings: Bundle): BackendProvider =
        GptProvider(context, settings.getInt("t2sThreads"), settings.getInt("vocoderThreads"), settings.getBoolean("allowNpu"))
}

internal class GptLibrary(private val context: Context) : VoiceLibrary {
    private val root by lazy { requireNotNull(context.getExternalFilesDir(null)) }
    private val catalog by lazy { OfficialModelCatalog(context) }
    private val shared by lazy { GptSharedResources(root, catalog, context::openBackendAsset) }
    override fun recoverInstallations() {
        shared.recoverRuntime()
        CheckpointImporter.recoverInterrupted(root)
    }
    override fun officialVoices() = catalog.models.map { OfficialVoice(it.ref, it.name, variantLabel(it.architecture)) }
    override fun variantLabel(variant: String) = modelArchitectureLabel(variant)
    override fun resourcePacks(cancel: () -> Unit) = shared.packs(cancel)
    override fun resourceHint(variant: String) = if (variant == "v5turbo") "engine_v5_resources" else "engine_resources"
    override val controls = listOf(
        OptionControl("speed", "speech_speed", "1.0", 0.6f, 1.65f, 21, unit = "×"),
        OptionControl("temperature", "temperature", "1.0", 0f, 1f, 100),
        OptionControl("topK", "top_k", "15", 1f, 100f, 99, integral = true),
        OptionControl("topP", "top_p", "1.0", 0f, 1f, 100),
        OptionControl("repetitionPenalty", "repetition_penalty", "1.35", 1f, 2f, 100),
    )
    override fun studioOptions(values: Map<String, String>) = SynthesisOptions.parse(values).toRequestOptions(1500)

    override fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext, progress: (String, Int?) -> Unit): Bundle {
        val app = this.context
        fun value(key: String) = requireNotNull(arguments.getString(key))
        fun open(key: String) = app.contentResolver.openInputStream(Uri.parse(value(key))) ?: errorUi(UiText("resource_open_failed"))
        fun stageCheckpoint(key: String, target: File): File {
            open(key).use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    context.checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    requireUi(total <= 2L * 1024 * 1024 * 1024) { UiText("checkpoint_size_limit") }
                    output.write(buffer, 0, count)
                }
            } }
            return target
        }
        fun report(label: UiText) = progress(label.resolve(app.resources), null)
        fun reportDownload(label: UiText, percent: Int?) = progress(label.resolve(app.resources), percent)
        val result = Bundle()
        when (action) {
            LibraryAction.PREVIEW_PAIR -> result.putString("variant", CheckpointPreview.read(app, Uri.parse(value("uri"))))
            LibraryAction.INSTALL_RESOURCES -> shared.install(arguments.getString("pack"), context::checkCancelled, ::reportDownload)
            LibraryAction.DOWNLOAD_VOICE -> OfficialModelInstaller(root, catalog, shared, app::openBackendAsset)
                .install(catalog.models.single { it.id == value("model") && it.architecture == value("variant") },
                    context::checkCancelled, ::reportDownload)
            LibraryAction.IMPORT_ARCHIVE -> open("uri").use { shared.importRuntime(it, context::checkCancelled) }
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
                    stageGpt = { stageCheckpoint("first", it) }, stageSovits = { stageCheckpoint("second", it) }, progress = ::report,
                    checkCancelled = context::checkCancelled, reference = reference,
                    prepareResources = { variant -> shared.forVoice(variant, context::checkCancelled, ::reportDownload) },
                    replace = arguments.getString("id")?.let { ModelRef("gptsovits", value("variant"), it) },
                    gptFilename = arguments.getString("firstName").orEmpty(), sovitsFilename = arguments.getString("secondName").orEmpty())
                result.putString("id", imported.characterId); result.putString("name", imported.displayName)
                result.putString("variant", imported.architecture)
            }
        }
        return result
    }
}
