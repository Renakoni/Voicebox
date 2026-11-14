package dev.rinstel.genie_tts.inference

import android.content.Context
import java.io.File
import org.json.JSONObject

class ModelAssetRepository private constructor(
    private val filesRoot: File,
) {
    constructor(context: Context) : this(requireNotNull(context.getExternalFilesDir(null)))

    constructor(filesRoot: File, marker: Unit = Unit) : this(filesRoot)

    private val modelRoot: File
        get() = File(filesRoot, "CharacterModels")

    fun modelRootDirectory(): File = modelRoot

    fun discoverCharacterModels(): List<CharacterModel> {
        val result = mutableListOf<CharacterModel>()

        val v2ProPlusRoot = File(modelRoot, "v2ProPlus")
        val v2ProPlusIds = v2ProPlusRoot
            .listFiles()
            ?.filter { File(it, "tts_models").isDirectory }
            ?.map { it.name }
            .orEmpty()
        result += CharacterModelCatalog.v2ProPlusFromCharacterIds(v2ProPlusIds)

        val v2Root = File(modelRoot, "v2")
        val v2Ids = v2Root
            .listFiles()
            ?.filter { File(it, "tts_models").isDirectory }
            ?.map { it.name }
            .orEmpty()
        result += CharacterModelCatalog.v2FromCharacterIds(v2Ids)

        return result.map { model ->
            val character = File(modelRoot, model.relativeModelDirectory).parentFile
            val imported = runCatching { JSONObject(File(character, "checkpoint-import.json").readText()) }.getOrNull()
            val displayName = listOf("prompt_wav.json", "checkpoint-import.json").firstNotNullOfOrNull { name ->
                runCatching { JSONObject(File(character, name).readText()).optString("display_name") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            }
            model.copy(displayName = displayName?.takeIf { it.isNotBlank() } ?: model.displayName,
                gptFilename = imported?.optString("gpt_filename").orEmpty(),
                sovitsFilename = imported?.optString("sovits_filename").orEmpty())
        }
    }

    fun inspect(requiredModelFiles: RequiredModelFiles): ModelInspectionResult {
        val modelDirectory = File(modelRoot, requiredModelFiles.assetSubdirectory)
        val present = modelDirectory.list()?.toSet().orEmpty()
        val missingFiles = requiredModelFiles.files.filterNot { fileName ->
            present.contains(fileName) ||
                requiredModelFiles.derivedFiles.any { d ->
                    d.outputFile == fileName && present.contains(d.sourceFile)
                }
        }
        val missingSessionModels = missingSessionModels(modelDirectory, requiredModelFiles)
        return ModelInspectionResult(
            presentFiles = present,
            missingFiles = missingFiles + missingSessionModels,
        )
    }

    fun installToPrivateStorage(requiredModelFiles: RequiredModelFiles, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val targetDirectory = File(modelRoot, requiredModelFiles.assetSubdirectory)
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        val versionFile = File(targetDirectory, ".genie-model-version")
        val expectedVersion = requiredModelFiles.storageVersion.toString()
        if (versionFile.exists() && versionFile.readText() == expectedVersion) {
            val present = targetDirectory.list()?.toSet().orEmpty()
            if (requiredModelFiles.files.all(present::contains) &&
                missingSessionModels(targetDirectory, requiredModelFiles).isEmpty()
            ) {
                return targetDirectory
            }
        }

        val present = targetDirectory.list()?.toSet().orEmpty()
        requiredModelFiles.files.forEach { fileName ->
            val targetFile = File(targetDirectory, fileName)
            if (targetFile.exists()) return@forEach

            val derivedFile = requiredModelFiles.derivedFiles.firstOrNull { it.outputFile == fileName }
            if (derivedFile != null) {
                val sourceFile = File(targetDirectory, derivedFile.sourceFile)
                if (sourceFile.exists()) {
                    HalfPrecisionFileConverter.convertFp16FileToFp32File(sourceFile, targetFile, progress)
                    return@forEach
                }
            }

            require(false) { "Missing model file: ${targetDirectory.absolutePath}/$fileName" }
        }

        val missingSessionModels = missingSessionModels(targetDirectory, requiredModelFiles)
        require(missingSessionModels.isEmpty()) {
            "Missing model file: ${targetDirectory.absolutePath}/${missingSessionModels.first()}"
        }

        versionFile.writeText(expectedVersion)
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
        val audioFile = File(promptDirectory, wav)
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
            File(modelDirectory, fileName).exists()
        }
}
