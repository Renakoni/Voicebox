package dev.renakoni.voicebox.inference

data class CharacterModel(
    val id: String,
    val displayName: String,
    val modelFiles: RequiredModelFiles,
    val gptFilename: String = "",
    val sovitsFilename: String = "",
) {
    val relativeModelDirectory: String
        get() = modelFiles.assetSubdirectory
}
