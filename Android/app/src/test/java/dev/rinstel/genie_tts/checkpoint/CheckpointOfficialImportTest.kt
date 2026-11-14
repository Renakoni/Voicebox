package dev.rinstel.genie_tts.checkpoint

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Opt-in integration check with the downloaded official pair and independent PyTorch oracle. */
class CheckpointOfficialImportTest {
    @Test fun officialPairMatchesDesktopConversion() {
        checkPair("s2Gv2ProPlus.pth")
    }

    @Test fun referenceIsInstalledWithModelAndOriginalFilesArePreserved() {
        checkPair("s2Gv2ProPlus.pth", withReference = true)
    }

    @Test fun fineTunedExportContainerMatchesDesktopConversion() {
        val path = System.getProperty("genieCheckpointValidationRoot")
        assumeNotNull(path)
        org.junit.Assume.assumeTrue(File(path!!, "s2Gv2ProPlus-export-style.pth").isFile)
        checkPair("s2Gv2ProPlus-export-style.pth")
    }

    private fun checkPair(sovitsName: String, withReference: Boolean = false) {
        val path = System.getProperty("genieCheckpointValidationRoot")
        assumeNotNull(path)
        val source = File(path!!)
        val root = Files.createTempDirectory("genie-official-import-").toFile()
        try {
            val assets = File("src/main/assets/checkpoint-v2proplus")
            val importer = CheckpointImporter(root) { File(assets, it).inputStream() }
            val sample = File(root, "source-reference.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val result = importer.import("官方 V2ProPlus",
                { File(source, "s1v3.ckpt").inputStream() }, { File(source, sovitsName).inputStream() },
                reference = if (withReference) CheckpointImporter.Reference(sample, " 参考原文。 ") else null,
                gptFilename = "s1v3.ckpt", sovitsFilename = sovitsName)
            val models = File(root, "CharacterModels/v2ProPlus/${result.characterId}/tts_models")
            val metadata = JSONObject(File(models.parentFile, "checkpoint-import.json").readText())
            assertEquals("s1v3.ckpt", metadata.getString("gpt_filename"))
            assertEquals(sovitsName, metadata.getString("sovits_filename"))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), sample.readBytes())
            assertTrue(File(source, "s1v3.ckpt").isFile)
            assertTrue(File(source, sovitsName).isFile)
            if (withReference) {
                assertArrayEquals(sample.readBytes(), File(models.parentFile, "prompt_wav/reference.wav").readBytes())
                val prompt = JSONObject(File(models.parentFile, "prompt_wav.json").readText()).getJSONObject("Normal")
                assertEquals("reference.wav", prompt.getString("wav"))
                assertEquals("参考原文。", prompt.getString("text"))
            } else assertFalse(File(models.parentFile, "prompt_wav.json").exists())
            val expected = JSONObject(File(source, "expected-import.json").readText()).getJSONObject("outputs")
            for (name in expected.keys()) {
                val file = File(models, name)
                assertEquals(name, expected.getJSONObject(name).getLong("bytes"), file.length())
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                assertEquals(name, expected.getJSONObject(name).getString("sha256"), CheckpointImporter.hex(digest.digest()))
            }
            assertTrue(File(models, "mobile-decoder-shared.onnx").isFile)
            assertTrue(File(models, "split-hifigan.onnx").isFile)
            assertEquals(setOf("CharacterModels", "source-reference.wav"), root.list()!!.toSet())
            assertFalse(requireNotNull(models.parentFile).walkTopDown().any { it.extension in setOf("ckpt", "pth") })
        } finally { root.deleteRecursively() }
    }
}
