package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ModelInspectionResult
import dev.renakoni.voicebox.bridge.ReferenceExample

import android.content.Context
import java.io.File
import org.json.JSONObject

class GptModelRepository(private val filesRoot: File) {
    constructor(context: Context) : this(requireNotNull(context.getExternalFilesDir(null)))

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
            val character = File(modelRoot, model.relativeModelDirectory).parentFile
            val imported = runCatching { JSONObject(File(character, "checkpoint-import.json").readText()) }.getOrNull()
            val displayName = runCatching { JSONObject(File(character, "prompt_wav.json").readText()).optString("display_name") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: imported?.optString("display_name")
            model.copy(displayName = displayName?.takeIf { it.isNotBlank() } ?: model.displayName,
                sourceFilenames = if (imported == null) emptyMap() else mapOf(
                    "gpt" to imported.optString("gpt_filename"), "sovits" to imported.optString("sovits_filename")))
        }
    }

    fun inspect(requiredModelFiles: RequiredModelFiles): ModelInspectionResult {
        val modelDirectory = File(modelRoot, requiredModelFiles.assetSubdirectory)
        val missingFiles = requiredModelFiles.files.filterNot { fileName ->
            File(modelDirectory, fileName).isFile ||
                requiredModelFiles.derivedFiles.any { d ->
                    d.outputFile == fileName && File(modelDirectory, d.sourceFile).isFile
                }
        }
        val missingSessionModels = missingSessionModels(modelDirectory, requiredModelFiles)
        return ModelInspectionResult(
            missingFiles = missingFiles + missingSessionModels,
        )
    }

    fun installToPrivateStorage(requiredModelFiles: RequiredModelFiles, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val targetDirectory = File(modelRoot, requiredModelFiles.assetSubdirectory)
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        requiredModelFiles.files.forEach { fileName ->
            val targetFile = File(targetDirectory, fileName)
            val derivedFile = requiredModelFiles.derivedFiles.firstOrNull { it.outputFile == fileName }
            if (derivedFile != null) {
                val sourceFile = File(targetDirectory, derivedFile.sourceFile)
                if (sourceFile.isFile) {
                    if (!targetFile.isFile || targetFile.length() != sourceFile.length() * 2L) {
                        HalfPrecisionFileConverter.convertFp16FileToFp32File(sourceFile, targetFile, progress)
                    }
                    return@forEach
                }
            }
            require(targetFile.isFile) { "Missing model file: ${targetDirectory.absolutePath}/$fileName" }
        }

        val missingSessionModels = missingSessionModels(targetDirectory, requiredModelFiles)
        require(missingSessionModels.isEmpty()) {
            "Missing model file: ${targetDirectory.absolutePath}/${missingSessionModels.first()}"
        }

        return targetDirectory
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

    private fun missingSessionModels(
        modelDirectory: File,
        requiredModelFiles: RequiredModelFiles,
    ): List<String> =
        requiredModelFiles.sessionModelNames(modelDirectory).filterNot { fileName ->
            File(modelDirectory, fileName).isFile
        }
}
