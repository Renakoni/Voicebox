package dev.rinstel.genie_tts

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rinstel.genie_tts.inference.ExecutionBackend
import dev.rinstel.genie_tts.inference.GenerationStage
import dev.rinstel.genie_tts.studio.StudioStore
import dev.rinstel.genie_tts.studio.StudioViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the installed resources and real engine; this is not a performance benchmark. */
@RunWith(AndroidJUnit4::class)
class StudioInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(ui.activity)[StudioViewModel::class.java]

    @Test
    fun referenceVoiceSurvivesRecreationAndGeneratesPlayableExport() {
        lateinit var vm: StudioViewModel
        ui.runOnIdle { vm = model() }
        ui.waitUntil(30_000) { !vm.loading && vm.connected }
        val originalId = vm.selectedId
        val originalText = vm.text
        val base = vm.voices.first { it.builtIn }
        var testId: String? = null
        try {
            ui.onNodeWithTag("tab_1").performClick()
            ui.onNodeWithTag("new_voice").performClick()
            ui.onNodeWithTag("save_voice").performClick()
            ui.onNodeWithText("请填好音色名称、参考音频和对应原文，并选择基础音色。").assertExists()
            ui.onNodeWithTag("voice_name").performScrollTo().performTextReplacement("界面验证音色")
            ui.runOnIdle { vm.pickReference(Uri.fromFile(File(base.audioPath))) }
            ui.waitUntil(15_000) { !vm.fileBusy }
            ui.runOnIdle { assertNull(vm.editorError); assertTrue(vm.draft!!.durationMs > 0) }
            ui.onNodeWithTag("reference_text").performScrollTo().performTextReplacement(base.transcript)
            ui.onNodeWithTag("save_voice").performClick()
            ui.waitUntil(15_000) { vm.draft == null && !vm.fileBusy }
            testId = vm.selectedId
            val saved = StudioStore(ui.activity).voices().first { it.id == testId }
            assertTrue(File(saved.audioPath).isFile)
            assertTrue(saved.audioPath.contains("studio_references"))
            assertFalse(saved.audioPath.contains("/cache/"))
            ui.activityRule.scenario.recreate()
            ui.runOnIdle { vm = model(); assertEquals(saved.id, vm.selectedId) }
            ui.onNodeWithTag("synthesis_text").performScrollTo()
                .performTextReplacement("今晚风很轻，我们沿着河边慢慢往前走。")
            ui.onNodeWithTag("generate").performScrollTo().performClick()
            ui.waitUntil(180_000) { !vm.busy && vm.output?.voiceName == saved.name }
            ui.runOnIdle {
                assertNull(vm.generationError)
                assertEquals(GenerationStage.COMPLETED, vm.state.stage)
                if (vm.gpuAvailable) assertEquals(ExecutionBackend.WEBGPU, vm.state.resolvedBackend)
            }
            val output = requireNotNull(vm.output)
            assertTrue(output.durationMs > 1000)
            val file = File(output.path)
            assertEquals("RIFF", file.inputStream().use { String(it.readNBytes(4)) })
            ui.onNodeWithTag("play_output").performScrollTo().performClick()
            ui.waitUntil(10_000) { vm.preview.playing && vm.preview.positionMs > 0 }
            ui.onNodeWithTag("play_output").performClick()
            ui.runOnIdle { assertFalse(vm.preview.playing) }
            val uri = FileProvider.getUriForFile(ui.activity, "${ui.activity.packageName}.audio", file)
            assertEquals(file.length(), ui.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().size.toLong() })
            val exported = File(ui.activity.cacheDir, "studio-test-export.wav")
            ui.runOnIdle { vm.export(Uri.fromFile(exported), file.absolutePath) }
            ui.waitUntil(10_000) { exported.exists() && exported.length() == file.length() }
            assertArrayEquals(file.readBytes(), exported.readBytes())
            exported.delete()
        } finally {
            ui.runOnIdle {
                vm.audio.stop()
                testId?.let { id -> vm.voices.firstOrNull { it.id == id }?.let(vm::deleteVoice) }
                vm.voices.firstOrNull { it.id == originalId }?.let(vm::select)
                vm.updateText(originalText)
            }
        }
    }

}
