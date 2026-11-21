package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ModelInspectionResult
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.core.openBackendAsset
import dev.renakoni.voicebox.core.regularFileAttributes
import dev.renakoni.voicebox.model.gptsovits.checkpoint.CheckpointImporter

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class GptModelRepository(private val filesRoot: File, private val openAsset: (String) -> InputStream) {
    constructor(context: Context) : this(requireNotNull(context.getExternalFilesDir(null)), context::openBackendAsset)
    private val weightSizes = ConcurrentHashMap<String, Map<String, Long>>()

    private val modelRoot: File
        get() = File(filesRoot, "CharacterModels")

    fun modelRootDirectory(): File = modelRoot

    fun discoverCharacterModels(): List<CharacterModel> {
        val result = listOf("v2ProPlus", "v2Pro", "v2", "v5turbo").flatMap { variant ->
            val ids = File(modelRoot, variant).listFiles().orEmpty()
                .filter { File(it, "tts_models").isDirectory }.map { it.name }
            CharacterModelCatalog.characters(variant, ids)
        }
        return result.map { model ->
            val directory = File(modelRoot, model.relativeModelDirectory)
            val character = directory.parentFile
            val imported = runCatching { JSONObject(File(character, "checkpoint-import.json").readText()) }.getOrNull()
            val displayName = runCatching { JSONObject(File(character, "prompt_wav.json").readText()).optString("display_name") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: imported?.optString("display_name")
            val webGpu = model.ref.variant == "v5turbo" ||
                listOf("split-vits.json", SplitVocoderAssets.ENCODER, SplitVocoderAssets.HIFIGAN).all { File(directory, it).isFile }
            model.copy(executionModes = if (webGpu) ExecutionBackend.entries.toSet() else setOf(ExecutionBackend.CPU),
                displayName = displayName?.takeIf { it.isNotBlank() } ?: model.displayName,
                sourceFilenames = if (imported == null) emptyMap() else mapOf(
                    "gpt" to imported.optString("gpt_filename"), "sovits" to imported.optString("sovits_filename")))
        }
    }

    fun inspect(requiredModelFiles: RequiredModelFiles): ModelInspectionResult {
        val modelDirectory = File(modelRoot, requiredModelFiles.assetSubdirectory)
        val required = requiredModelFiles.files + requiredModelFiles.sessionModels
        if (!modelDirectory.isDirectory) return ModelInspectionResult(required)
        val variant = requiredModelFiles.assetSubdirectory.substringBefore('/')
        // Original V2 uses prepared exports. The checkpoint-pair variants have fixed template shapes.
        val sizes = if (variant == "v2") emptyMap() else weightSizes.computeIfAbsent(variant) {
            val groups = CheckpointImporter.readManifest(it, openAsset).getJSONArray("groups")
            (0 until groups.length()).associate { index ->
                val group = groups.getJSONObject(index)
                val tensors = group.getJSONArray("tensors")
                CheckpointImporter.weightFilename(group) to
                    (0 until tensors.length()).sumOf { tensor -> tensors.getJSONObject(tensor).getLong("elements") * Float.SIZE_BYTES }
            }
        }
        val missing = requiredModelFiles.files.filterNot { name ->
            val derived = requiredModelFiles.derivedFiles.firstOrNull { it.outputFile == name }
            val sourceBytes = derived?.let { File(modelDirectory, it.sourceFile).regularFileAttributes()?.size() }
            if (sourceBytes != null) return@filterNot sourceBytes > 0 && sourceBytes % 2 == 0L
            val bytes = File(modelDirectory, name).regularFileAttributes()?.size() ?: return@filterNot false
            val expected = sizes[name]
            if (expected != null) bytes == expected else bytes > 0 && bytes % Float.SIZE_BYTES == 0L
        }
        val missingGraphs = requiredModelFiles.sessionModels.filter { (File(modelDirectory, it).regularFileAttributes()?.size() ?: 0) == 0L }
        return ModelInspectionResult(missing + missingGraphs)
    }

    fun prepareExportedWeights(files: RequiredModelFiles, progress: (Long, Long) -> Unit) {
        val directory = File(modelRoot, files.assetSubdirectory)
        for (derived in files.derivedFiles) {
            val source = File(directory, derived.sourceFile)
            val target = File(directory, derived.outputFile)
            if (source.isFile) HalfPrecisionFileConverter.prepare(filesRoot, source, target, progress)
        }
    }

    fun findReferenceExample(characterModel: CharacterModel): ReferenceExample? {
        // Reference assets live next to each character's tts_models directory for both v2 and v2ProPlus.
        val characterRoot = File(modelRoot, characterModel.relativeModelDirectory).parentFile
            ?: return null
        val promptJson = File(characterRoot, "prompt_wav.json")
        val promptDirectory = File(characterRoot, "prompt_wav")

        require(promptJson.isFile) {
            "${promptJson.absolutePath} is required to select the Normal reference."
        }

        val normal = readNormalPromptFields(promptJson.readText())
            ?: throw IllegalArgumentException(
                "${promptJson.name} must contain a valid Normal reference object.",
            )
        val wav = normal["wav"].orEmpty().trim()
        val text = normal["text"]
        require(wav.isNotBlank() && text?.isNotBlank() == true) {
            "${promptJson.name} Normal reference must contain wav and text."
        }
        val audioFile = File(promptDirectory, wav).canonicalFile
        require(audioFile.toPath().startsWith(promptDirectory.canonicalFile.toPath())) {
            "Normal reference audio must stay inside prompt_wav."
        }
        require(audioFile.isFile) {
            "Normal reference audio is missing: ${audioFile.absolutePath}"
        }
        return ReferenceExample(audioFile = audioFile, referenceText = text, language = normal["language"] ?: "auto")
    }

    private fun readNormalPromptFields(jsonText: String): Map<String, String?>? {
        val normal = runCatching { JSONObject(jsonText).optJSONObject("Normal") }.getOrNull()
        if (normal != null) {
            val wav = normal.optString("wav").trim()
            val text = normal.takeIf { it.has("text") && !it.isNull("text") }?.optString("text")
            return mapOf("wav" to wav, "text" to text, "language" to normal.optString("language", "auto"))
        }

        return null
    }
}
