package dev.rinstel.genie_tts.checkpoint

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CharacterVoiceEditorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun character(version: String = "v2ProPlus"): File =
        File(temporary.root, "CharacterModels/$version/voice").apply {
            File(this, "tts_models").mkdirs()
            File(this, "tts_models/weights.bin").writeText("unchanged weights")
            File(this, "prompt_wav").mkdirs()
            File(this, "prompt_wav/old.wav").writeText("original audio")
            File(this, "prompt_wav.json").writeText("""{"Normal":{"wav":"old.wav","text":"原文","language":"zh"}}""")
        }

    @Test fun metadataEditRetainsAudioAndWeightsForBothSupportedVersions() {
        listOf("v2", "v2ProPlus").forEach { version ->
            val voice = character(version)
            val audio = File(voice, "prompt_wav/old.wav")
            val weights = File(voice, "tts_models/weights.bin")
            val timestamp = weights.lastModified()
            CharacterVoiceEditor(temporary.root).updateReference("voice", " 新名称 ",
                CheckpointImporter.Reference(audio, " こんにちは。 ", "ja"), version)
            val manifest = JSONObject(File(voice, "prompt_wav.json").readText())
            assertEquals("新名称", manifest.getString("display_name"))
            assertEquals("こんにちは。", manifest.getJSONObject("Normal").getString("text"))
            assertEquals("ja", manifest.getJSONObject("Normal").getString("language"))
            assertEquals("old.wav", manifest.getJSONObject("Normal").getString("wav"))
            assertEquals("unchanged weights", weights.readText())
            assertEquals(timestamp, weights.lastModified())
            assertEquals(listOf("old.wav"), File(voice, "prompt_wav").list()!!.toList())
        }
    }

    @Test fun replacingAudioRemovesOnlyTheUnusedPreviousReference() {
        val voice = character()
        val source = temporary.newFile("source.wav").apply { writeText("new audio") }
        val editor = CharacterVoiceEditor(temporary.root)
        editor.updateReference("voice", "名称", CheckpointImporter.Reference(source, "原文", "zh"))
        val manifest = JSONObject(File(voice, "prompt_wav.json").readText())
        val newFile = File(voice, "prompt_wav/${manifest.getJSONObject("Normal").getString("wav")}")
        assertEquals(source.readText(), newFile.readText())
        assertFalse(File(voice, "prompt_wav/old.wav").exists())
        assertTrue(source.isFile)
        manifest.put("Happy", JSONObject().put("wav", newFile.name).put("text", "原文"))
        File(voice, "prompt_wav.json").writeText(manifest.toString())
        editor.updateReference("voice", "名称", CheckpointImporter.Reference(source, "原文", "zh"))
        assertTrue("Other emotions still use this audio", newFile.isFile)
    }

    @Test fun invalidReferenceOrDirectoryLeavesInstalledFilesUnchanged() {
        val voice = character()
        val manifest = File(voice, "prompt_wav.json")
        val before = manifest.readText()
        val editor = CharacterVoiceEditor(temporary.root)
        assertThrows(IllegalArgumentException::class.java) {
            editor.updateReference("voice", "名称", CheckpointImporter.Reference(File(voice, "missing.wav"), "原文"))
        }
        assertThrows(IllegalArgumentException::class.java) { editor.character("../voice") }
        assertThrows(IllegalArgumentException::class.java) { editor.character("voice", "../v2ProPlus") }
        assertEquals(before, manifest.readText())
        assertEquals(setOf("prompt_wav", "prompt_wav.json", "tts_models"), voice.list()!!.toSet())
    }

    @Test fun replacementPublishesOnlyPreparedFilesAndRemovesOldWeights() {
        val voice = character()
        val stage = temporary.newFolder("stage")
        val prepared = File(stage, "voice").apply { mkdir(); File(this, "new.bin").writeText("new weights") }
        CharacterVoiceEditor.replaceDirectory(prepared, voice)
        assertEquals(listOf("new.bin"), voice.list()!!.toList())
        assertEquals("new weights", File(voice, "new.bin").readText())
        assertEquals(0, stage.list()!!.size)
    }

    @Test fun invalidReplacementDoesNotMoveInstalledModel() {
        val voice = character()
        assertThrows(IllegalArgumentException::class.java) {
            CharacterVoiceEditor.replaceDirectory(File(temporary.root, "missing"), voice)
        }
        assertEquals("unchanged weights", File(voice, "tts_models/weights.bin").readText())
    }
}
