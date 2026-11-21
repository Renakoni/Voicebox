package dev.renakoni.voicebox.model.gptsovits

import java.io.File

data class RequiredModelFiles(
    val variantName: String,
    val assetSubdirectory: String,
    val files: List<String>,
    val sessionModels: List<String>,
    val optionalSessionModels: List<String> = emptyList(),
    val derivedFiles: List<DerivedModelFile> = emptyList(),
    val storageVersion: Int = 3,
    val maxAuxiliaryReferenceCount: Int = 0,
) {

    fun sessionModelNames(modelDirectory: File): List<String> =
        sessionModels + optionalSessionModels.filter { File(modelDirectory, it).isFile }

    companion object {
        fun v2ProPlusCharacter(characterId: String): RequiredModelFiles = RequiredModelFiles(
            variantName = "${characterId.replaceFirstChar { it.uppercase() }} (V2ProPlus)",
            assetSubdirectory = "v2ProPlus/$characterId/tts_models",
            files = listOf(
                "prompt_encoder_fp32.bin",
                "t2s_encoder_fp32.bin",
                "t2s_shared_fp32.bin",
                "vits_fp32.bin",
            ),
            sessionModels = listOf(
                "t2s_encoder_fp32.onnx",
                "t2s_first_stage_decoder_fp32.onnx",
                "t2s_stage_decoder_fp32.onnx",
                "prompt_encoder_fp32.onnx",
                "vits_fp32.onnx",
            ),
            optionalSessionModels = listOf("prompt_encoder_multi_fp32.onnx"),
            // The graph reserves one slot for the primary reference.
            maxAuxiliaryReferenceCount = 7,
        )

        fun v2ProCharacter(characterId: String): RequiredModelFiles = v2ProPlusCharacter(characterId).copy(
            variantName = "${characterId.replaceFirstChar { it.uppercase() }} (V2Pro)",
            assetSubdirectory = "v2Pro/$characterId/tts_models",
        )

        fun v2Character(characterId: String): RequiredModelFiles = RequiredModelFiles(
            variantName = "${characterId.replaceFirstChar { it.uppercase() }} (V2)",
            assetSubdirectory = "v2/$characterId/tts_models",
            files = listOf(
                "t2s_encoder_fp32.bin",
                "t2s_shared_fp32.bin",
                "vits_fp32.bin",
            ),
            sessionModels = listOf(
                "t2s_encoder_fp32.onnx",
                "t2s_first_stage_decoder_fp32.onnx",
                "t2s_stage_decoder_fp32.onnx",
                "vits_fp32.onnx",
            ),
            derivedFiles = listOf(
                DerivedModelFile("t2s_shared_fp16.bin", "t2s_shared_fp32.bin"),
                DerivedModelFile("vits_fp16.bin", "vits_fp32.bin"),
            ),
        )

        fun v5TurboCharacter(characterId: String): RequiredModelFiles = RequiredModelFiles(
            variantName = "$characterId (V5 Turbo)",
            assetSubdirectory = "v5turbo/$characterId/tts_models",
            files = listOf("t2s_encoder_fp32.bin", "t2s_shared_fp32.bin", "v5_sovits_fp32.bin"),
            sessionModels = listOf("t2s_encoder_fp32.onnx", "t2s_first_stage_decoder_fp32.onnx", "t2s_stage_decoder_fp32.onnx",
                "v5-reference-audio.onnx", "v5-condition.onnx", "v5-static.onnx", "v5-dit.onnx"),
        )
    }
}
