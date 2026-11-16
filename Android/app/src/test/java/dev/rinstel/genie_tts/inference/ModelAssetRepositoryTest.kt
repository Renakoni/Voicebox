package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelAssetRepositoryTest {
    @Test
    fun rebuildsTruncatedExpandedWeights() {
        val root = java.nio.file.Files.createTempDirectory("genie-partial-").toFile()
        try {
            val files = RequiredModelFiles("test", "test", listOf("weights.bin"), listOf("model.onnx"),
                derivedFiles = listOf(DerivedModelFile("weights.fp16", "weights.bin")))
            val directory = File(root, "CharacterModels/test").apply { mkdirs() }
            File(directory, "model.onnx").writeBytes(byteArrayOf(0))
            File(directory, "weights.fp16").writeBytes(byteArrayOf(0, 0x3c))
            val output = File(directory, "weights.bin").apply { writeBytes(byteArrayOf(0)) }

            ModelAssetRepository(root).installToPrivateStorage(files)

            org.junit.Assert.assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f), output.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun discoversOriginalCheckpointFilenames() {
        val root = java.nio.file.Files.createTempDirectory("genie-filenames-").toFile()
        try {
            val models = File(root, "CharacterModels/v2ProPlus/example/tts_models").apply { mkdirs() }
            val metadata = File(models.parentFile, "checkpoint-import.json")
            metadata.writeText("""{"display_name":"音色","gpt_filename":"模型名称-e15.ckpt","sovits_filename":"模型名称-e8-s120.pth"}""")
            val model = ModelAssetRepository(root).discoverCharacterModels().single()
            assertEquals("模型名称-e15.ckpt", model.gptFilename)
            assertEquals("模型名称-e8-s120.pth", model.sovitsFilename)
        } finally { root.deleteRecursively() }
    }


    @Test
    fun selectsNormalReferenceWhenAnotherEmotionComesFirst() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "angry.wav").writeBytes(byteArrayOf(0))
        File(promptDirectory, "normal.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav.json").writeText(
            """
            {
              "Angry": {
                "wav": "angry.wav",
                "text": "angry text"
              },
              "Normal": {
                "wav": "normal.wav",
                "text": "normal text",
                "language": "ja"
              }
            }
            """.trimIndent(),
        )

        val example = ModelAssetRepository(filesRoot).findReferenceExample(modelV2ProPlus("mansui"))

        assertEquals("normal text", example?.referenceText)
        assertEquals("ja", example?.language)
        assertEquals("normal.wav", example?.audioFile?.name)
    }


    @Test
    fun rejectsInvalidReferenceMetadataInsteadOfPickingFirstAudio() {
        val root = java.nio.file.Files.createTempDirectory("genie-reference-").toFile()
        try {
            val directory = File(root, "CharacterModels/v2ProPlus/mansui").apply { mkdirs() }
            File(directory, "prompt_wav/normal.wav").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0)) }
            File(directory, "outside.wav").writeBytes(byteArrayOf(0))
            val json = File(directory, "prompt_wav.json")
            for (content in listOf("""{"Normal":{"wav":"../outside.wav","text":"reference"}}""", null, "{", """{"Angry":{"wav":"normal.wav","text":"angry"}}""",
                """{"Normal":{"wav":"normal.wav","text":"   "}}""",
                """{"Normal":{"wav":"missing.wav","text":"reference"}}""")) {
                if (content == null) json.delete() else json.writeText(content)
                val error = runCatching {
                    ModelAssetRepository(root).findReferenceExample(modelV2ProPlus("mansui"))
                }.exceptionOrNull()
                assertTrue("Expected invalid reference to fail: $content", error is IllegalArgumentException)
            }
        } finally { root.deleteRecursively() }
    }


    @Test
    fun reportsMissingSessionOnnxFiles() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val modelFiles = RequiredModelFiles.v2ProPlusCharacter("mansui")
        val modelDirectory = File(filesRoot, "CharacterModels/${modelFiles.assetSubdirectory}").apply { mkdirs() }
        modelFiles.files.forEach { fileName ->
            File(modelDirectory, fileName).writeBytes(byteArrayOf(0))
        }

        val inspection = ModelAssetRepository(filesRoot).inspect(modelFiles)

        assertTrue(inspection.missingFiles.contains("t2s_first_stage_decoder_fp32.onnx"))
    }

    @Test
    fun prefersFp32SynthesisGraphsByDefault() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val modelFiles = RequiredModelFiles.v2ProPlusCharacter("mansui")
        val modelDirectory = File(filesRoot, "CharacterModels/${modelFiles.assetSubdirectory}").apply { mkdirs() }
        modelFiles.sessionModels.forEach { fileName ->
            File(modelDirectory, fileName).writeBytes(byteArrayOf(0))
        }
        modelFiles.sessionModelVariants.values.forEach { fileName ->
            File(modelDirectory, fileName).writeBytes(byteArrayOf(0))
        }

        val selected = modelFiles.sessionModelNames(modelDirectory)

        assertTrue(selected.contains("t2s_first_stage_decoder_fp32.onnx"))
        assertTrue(selected.contains("t2s_stage_decoder_fp32.onnx"))
        assertTrue(selected.contains("vits_fp32.onnx"))
        assertFalse(selected.contains("t2s_first_stage_decoder_fp16.onnx"))
        assertFalse(selected.contains("t2s_stage_decoder_fp16.onnx"))
        assertFalse(selected.contains("vits_fp16.onnx"))
    }

    @Test
    fun inspectionAcceptsFp16SynthesisGraphsAsSessionReplacements() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val modelFiles = RequiredModelFiles.v2ProPlusCharacter("mansui")
        val modelDirectory = File(filesRoot, "CharacterModels/${modelFiles.assetSubdirectory}").apply { mkdirs() }
        modelFiles.files.forEach { fileName ->
            File(modelDirectory, fileName).writeBytes(byteArrayOf(0))
        }
        modelFiles.sessionModels
            .filterNot { it in modelFiles.sessionModelVariants.keys }
            .forEach { fileName ->
                File(modelDirectory, fileName).writeBytes(byteArrayOf(0))
            }
        modelFiles.sessionModelVariants.values.forEach { fileName ->
            File(modelDirectory, fileName).writeBytes(byteArrayOf(0))
        }

        val inspection = ModelAssetRepository(filesRoot).inspect(modelFiles)

        assertFalse(inspection.missingFiles.contains("t2s_first_stage_decoder_fp32.onnx"))
        assertFalse(inspection.missingFiles.contains("t2s_stage_decoder_fp32.onnx"))
        assertFalse(inspection.missingFiles.contains("vits_fp32.onnx"))
    }

    private fun modelV2ProPlus(id: String): CharacterModel =
        CharacterModelCatalog.v2ProPlusFromCharacterIds(listOf(id)).first()

}
