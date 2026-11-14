package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ModelAssetRepositoryTest {
    @Test
    fun discoversOriginalCheckpointFilenamesAndDoesNotInventLegacyNames() {
        val root = java.nio.file.Files.createTempDirectory("genie-filenames-").toFile()
        try {
            val models = File(root, "CharacterModels/v2ProPlus/example/tts_models").apply { mkdirs() }
            val metadata = File(models.parentFile, "checkpoint-import.json")
            metadata.writeText("""{"display_name":"音色","gpt_filename":"模型名称-e15.ckpt","sovits_filename":"模型名称-e8-s120.pth"}""")
            val model = ModelAssetRepository(root).discoverCharacterModels().single()
            assertEquals("模型名称-e15.ckpt", model.gptFilename)
            assertEquals("模型名称-e8-s120.pth", model.sovitsFilename)
            metadata.writeText("""{"display_name":"旧音色"}""")
            val legacy = ModelAssetRepository(root).discoverCharacterModels().single()
            assertEquals("", legacy.gptFilename)
            assertEquals("", legacy.sovitsFilename)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun loadsReferenceExampleFromPromptJson() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "sample.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav.json").writeText(
            """
            {
              "Normal": {
                "wav": "sample.wav",
                "text": "reference text"
              }
            }
            """.trimIndent(),
        )

        val repository = ModelAssetRepository(filesRoot)
        val example = repository.findReferenceExample(modelV2ProPlus("mansui"))

        assertNotNull(example)
        assertEquals("reference text", example?.referenceText)
        assertEquals("auto", example?.language)
        assertTrue(example?.audioFile?.absolutePath?.endsWith("sample.wav") == true)
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
    fun decodesEscapedNormalReferenceTextWithoutMixingEmotionFields() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "normal.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav.json").writeText(
            """
            {
              "Happy": {
                "wav": "missing.wav",
                "text": "happy text"
              },
              "Normal": {
                "wav": "normal.wav",
                "text": "She said: \"hello\"\nthen left."
              }
            }
            """.trimIndent(),
        )

        val example = ModelAssetRepository(filesRoot).findReferenceExample(modelV2ProPlus("mansui"))

        assertEquals("She said: \"hello\"\nthen left.", example?.referenceText)
        assertEquals("normal.wav", example?.audioFile?.name)
    }

    @Test
    fun rejectsPromptJsonWithoutNormalInsteadOfPickingAnotherEmotion() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "angry.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav.json").writeText(
            """
            {
              "Angry": {
                "wav": "angry.wav",
                "text": "angry text"
              }
            }
            """.trimIndent(),
        )

        try {
            ModelAssetRepository(filesRoot).findReferenceExample(modelV2ProPlus("mansui"))
            fail("Missing Normal reference must be reported")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("Normal"))
        }
    }

    @Test
    fun rejectsMalformedPromptJsonInsteadOfPickingFirstAudio() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "angry.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav.json").writeText(
            "{ \"Angry\": { \"wav\": \"angry.wav\" }",
        )

        try {
            ModelAssetRepository(filesRoot).findReferenceExample(modelV2ProPlus("mansui"))
            fail("Malformed prompt JSON must be reported")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("prompt_wav.json"))
        }
    }

    @Test
    fun rejectsNormalReferenceWithBlankText() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "normal.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav.json").writeText(
            """
            {
              "Normal": {
                "wav": "normal.wav",
                "text": "   "
              }
            }
            """.trimIndent(),
        )

        try {
            ModelAssetRepository(filesRoot).findReferenceExample(modelV2ProPlus("mansui"))
            fail("Blank Normal reference text must be reported")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("Normal reference"))
        }
    }

    @Test
    fun rejectsMissingPromptJsonInsteadOfPickingFirstAudio() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2ProPlus/mansui/prompt_wav").apply { mkdirs() }
        val wavFile = File(promptDirectory, "001-reference text.wav")
        wavFile.writeBytes(byteArrayOf(0))

        try {
            ModelAssetRepository(filesRoot).findReferenceExample(modelV2ProPlus("mansui"))
            fail("Missing prompt_wav.json must be reported")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("prompt_wav.json"))
        }
    }

    @Test
    fun loadsReferenceExampleFromV2CharacterDirectory() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val promptDirectory = File(filesRoot, "CharacterModels/v2/Alice_CN/prompt_wav").apply { mkdirs() }
        File(promptDirectory, "sample.wav").writeBytes(byteArrayOf(0))
        File(filesRoot, "CharacterModels/v2/Alice_CN/prompt_wav.json").writeText(
            """
            {
              "Normal": {
                "wav": "sample.wav",
                "text": "reference text"
              }
            }
            """.trimIndent(),
        )

        val repository = ModelAssetRepository(filesRoot)
        val example = repository.findReferenceExample(modelV2("Alice_CN"))

        assertNotNull(example)
        assertEquals("reference text", example?.referenceText)
        assertTrue(example?.audioFile?.absolutePath?.endsWith("sample.wav") == true)
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
    fun prefersAvailableFp16SynthesisGraphs() {
        val filesRoot = java.nio.file.Files.createTempDirectory("genie-test-").toFile()
        val modelFiles = RequiredModelFiles.v2ProPlusCharacter("mansui")
        val modelDirectory = File(filesRoot, "CharacterModels/${modelFiles.assetSubdirectory}").apply { mkdirs() }
        File(modelDirectory, "t2s_first_stage_decoder_fp16.onnx").writeBytes(byteArrayOf(0))
        File(modelDirectory, "t2s_stage_decoder_fp16.onnx").writeBytes(byteArrayOf(0))
        File(modelDirectory, "vits_fp16.onnx").writeBytes(byteArrayOf(0))

        val selected = modelFiles.sessionModelNames(modelDirectory, preferFp16 = true)

        assertTrue(selected.contains("t2s_first_stage_decoder_fp16.onnx"))
        assertTrue(selected.contains("t2s_stage_decoder_fp16.onnx"))
        assertTrue(selected.contains("vits_fp16.onnx"))
        assertFalse(selected.contains("t2s_first_stage_decoder_fp32.onnx"))
        assertFalse(selected.contains("t2s_stage_decoder_fp32.onnx"))
        assertFalse(selected.contains("vits_fp32.onnx"))
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

    private fun modelV2(id: String): CharacterModel =
        CharacterModelCatalog.v2FromCharacterIds(listOf(id)).first()
}
