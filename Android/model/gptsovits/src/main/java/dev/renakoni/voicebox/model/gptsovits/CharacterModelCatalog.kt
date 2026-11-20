package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ModelRef

object CharacterModelCatalog {
    fun characters(variant: String, ids: List<String>): List<CharacterModel> =
        ids.distinct().sorted().map { id ->
            val ref = ModelRef("gptsovits", variant, id)
            val files = requiredFiles(ref)
            CharacterModel(ref, id, files.assetSubdirectory, files.maxAuxiliaryReferenceCount)
        }
}

private fun requiredFiles(ref: ModelRef): RequiredModelFiles = when (ref.variant) {
    "v2" -> RequiredModelFiles.v2Character(ref.modelId)
    "v2Pro" -> RequiredModelFiles.v2ProCharacter(ref.modelId)
    "v2ProPlus" -> RequiredModelFiles.v2ProPlusCharacter(ref.modelId)
    "v5turbo" -> RequiredModelFiles.v5TurboCharacter(ref.modelId)
    else -> error("Unknown GPT-SoVITS variant: ${ref.variant}")
}

val CharacterModel.modelFiles get() = requiredFiles(ref)
