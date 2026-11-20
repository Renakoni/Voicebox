package dev.renakoni.voicebox

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.renakoni.voicebox.studio.StudioViewModel
import dev.renakoni.voicebox.bridge.GenerationStage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelsInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsOpensSecondaryModelPageAndReturns() {
        ui.onNodeWithTag("tab_2").performClick()
        ui.onNodeWithTag("models").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("models_page").assertIsDisplayed()
        ui.onNodeWithText(ui.activity.getString(R.string.ming_name)).assertIsDisplayed()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("gptsovits_installed").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("GPT-SoVITS").assertIsDisplayed()
        ui.onNodeWithTag("gptsovits_installed").assertIsDisplayed()
        assertTrue(ui.onAllNodesWithTag("ming_download").fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithTag("ming_installed").fetchSemanticsNodes().isNotEmpty())
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.back_settings)).performClick()
        ui.onNodeWithTag("models").assertIsDisplayed()
        ui.onNodeWithTag("advanced_settings").assertExists()
    }

    @Test fun installedMingUsesTheExistingCreateFlow() {
        lateinit var vm: StudioViewModel
        ui.runOnIdle { vm = ViewModelProvider(ui.activity)[StudioViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.loading && vm.connected }
        assumeTrue("Run the runtime installation test first", vm.models.any { it.ref.backendId == "ming" })
        ui.runOnIdle { vm.chooseVoice(vm.voices.first { it.builtIn && it.model.backendId == "ming" }) }
        ui.onNodeWithTag("synthesis_text").performScrollTo().performTextReplacement("你好，欢迎使用本地语音。")
        ui.onNodeWithTag("advanced_settings").assertDoesNotExist()
        ui.waitUntil(30_000) { vm.canGenerate }
        ui.onNodeWithTag("generate").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(300_000) {
            !vm.busy && (vm.state.stage == GenerationStage.ERROR ||
                vm.state.stage == GenerationStage.COMPLETED && vm.output?.path != null && vm.output?.path == vm.state.latestOutputFilePath)
        }
        ui.runOnIdle {
            assertNull(vm.generationError)
            assertEquals(GenerationStage.COMPLETED, vm.state.stage)
            assertTrue(vm.output!!.durationMs > 0)
        }
        val play = ui.onNodeWithTag("play_output").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.runOnUiThread { play() }
        ui.waitUntil(10_000) { vm.preview.playing && vm.preview.positionMs > 0 }
    }
}
