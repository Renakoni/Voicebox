package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.FilePin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GptSharedResourcesRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root
    private val runtime get() = File(root, "RuntimeAssets")
    private val common = GptSharedResources.COMMON
    private val vocoder = GptSharedResources.VOCODER
    private val commonOutput = listOf(RuntimeAssetRepository.HUBERT_MANIFEST,
        "language_frontends/english/encoder.onnx", "language_frontends/english/decoder.onnx",
        "language_frontends/english/names.tsv", "language_frontends/english/dictionary.tsv",
        "language_frontends/japanese/sys.dic")
    private val commonDownload = "speaker_encoder.onnx"
    private val vocoderOutput = "$vocoder/v5_vocoder_fp32.bin"
    private val pin = FilePin(1, "a".repeat(64))
    private val allAssets = commonOutput + commonDownload + vocoderOutput

    @Test fun removingCommonClearsItsFilesAndPreservesTheVocoderAndVoices() {
        val shared = installed()
        val derived = file("RuntimeAssets/${RuntimeAssetRepository.HUBERT_FP32}")
        val download = file("RuntimeAssets/.$commonDownload.download")
        val languageSource = file(".gpt-packages/languages/checkpoint20.npz")
        val languageReceipt = shared.files.receipt(languageSource)
        shared.files.record(languageSource, pin)
        val referenceCache = file("ReferenceAudioCache/generated.bin")
        val voice = file("CharacterModels/v2/personal/weights.bin")
        val voiceSource = file(".gpt-packages/voices/s1v3.ckpt")
        val unknown = file("RuntimeAssets/language_frontends/english/local-notes.txt")
        val vocoderReceipt = File(root, ".gpt-packages/receipts/RuntimeAssets/$vocoderOutput.lock")
        val commonReceipt = File(root, ".gpt-packages/receipts/RuntimeAssets/$commonDownload.lock")

        shared.remove(common)

        assertPacks(shared, commonInstalled = false, vocoderInstalled = true)
        assertEquals(setOf(vocoderOutput), ResourcePackageImporter.readPins(runtime).keys)
        (commonOutput + commonDownload).forEach { assertFalse(File(runtime, it).exists()) }
        listOf(derived, download, languageSource, languageReceipt, referenceCache, commonReceipt).forEach { assertFalse(it.exists()) }
        listOf(voice, voiceSource, unknown, vocoderReceipt).forEach { assertTrue(it.isFile) }

        shared.remove(vocoder)
        assertFalse(File(runtime, ".imported-runtime.json").exists())
    }

    @Test fun removingVocoderClearsRecoveryAndScratchFilesAndPreservesCommonResources() {
        val shared = installed()
        val previous = File(root, ".gpt-packages/archive-import/.previous/$vocoder").apply { parentFile!!.mkdirs() }
        Files.move(File(runtime, vocoder).toPath(), previous.toPath())
        val source = file(".gpt-packages/vocoder.pth")
        val download = file(".gpt-packages/.vocoder.pth.download")
        val receipt = File(root, ".gpt-packages/receipts/RuntimeAssets/$vocoderOutput.lock")

        shared.remove(vocoder)
        shared.recoverRuntime()

        assertPacks(shared, commonInstalled = true, vocoderInstalled = false)
        assertEquals((commonOutput + commonDownload).toSet(), ResourcePackageImporter.readPins(runtime).keys)
        listOf(File(runtime, vocoderOutput), previous, source, download, receipt).forEach { assertFalse(it.exists()) }
    }

    private fun installed(): GptSharedResources {
        fun filePin() = JSONObject().put("bytes", pin.bytes).put("sha256", pin.sha256)
        fun source(path: String) = filePin().put("path", path).put("urls", JSONArray().put("https://example.test/$path"))
        val catalog = OfficialModelCatalog(JSONObject().put("gpt", source("gpt.ckpt")).put("vocoder", source("vocoder.pth"))
            .put("runtime", JSONArray().put(source(commonDownload)))
            .put("languageFrontends", JSONArray().put(source("checkpoint20.npz")).put(source("open_jtalk_dic_utf_8-1.11.tar.gz")))
            .put("models", JSONArray()))
        val inventory = JSONObject()
        val outputs = JSONObject()
        (commonOutput + vocoderOutput).forEach { outputs.put(it, JSONArray().put(filePin())) }
        val shared = GptSharedResources(root, catalog) { name ->
            check(name == "official-runtime/resources.lock.json")
            JSONObject().put("outputs", outputs).toString().byteInputStream()
        }
        allAssets.forEach {
            shared.files.record(file("RuntimeAssets/$it"), pin)
            inventory.put(it, filePin())
        }
        File(runtime, ".imported-runtime.json").writeText(inventory.toString())
        assertPacks(shared, commonInstalled = true, vocoderInstalled = true)
        return shared
    }

    private fun file(path: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1))
    }

    private fun assertPacks(shared: GptSharedResources, commonInstalled: Boolean, vocoderInstalled: Boolean) {
        assertEquals(mapOf(common to commonInstalled, vocoder to vocoderInstalled),
            shared.packs { }.associate { it.id to it.installed })
    }
}
