package dev.renakoni.voicebox.bridge

/** A model belongs to a backend and a variant. User-created voices have their own IDs. */
data class ModelRef(val backendId: String, val variant: String, val modelId: String)

data class CharacterModel(
    val ref: ModelRef,
    val displayName: String,
    val relativeModelDirectory: String,
    val maxAuxiliaryReferences: Int = 0,
    val sourceFilenames: Map<String, String> = emptyMap(),
) {
    val id get() = ref.modelId
}
