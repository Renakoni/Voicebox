package dev.rinstel.genie_tts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rinstel.genie_tts.studio.StudioViewModel
import dev.rinstel.genie_tts.inference.GenerationStage
import dev.rinstel.genie_tts.moremodels.MingModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoreModelsInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsOpensSecondaryModelPageAndReturns() {
        ui.onNodeWithTag("tab_2").performClick()
        ui.onNodeWithTag("more_models").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("more_models_page").assertIsDisplayed()
        ui.onNodeWithText(ui.activity.getString(R.string.ming_name)).assertIsDisplayed()
        ui.onNodeWithTag("ming_download").assertIsDisplayed()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.back_settings)).performClick()
        ui.onNodeWithTag("more_models").assertIsDisplayed()
        ui.onNodeWithTag("advanced_settings").assertExists()
    }

    @Test fun installedMingUsesTheExistingCreateFlow() {
        lateinit var vm: StudioViewModel
        ui.runOnIdle { vm = ViewModelProvider(ui.activity)[StudioViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.loading && vm.connected }
        assumeTrue("Run the runtime installation test first", vm.mingInstalled)
        ui.runOnIdle { vm.chooseVoice(vm.voices.first { it.builtIn && it.modelDirectory == MingModel.DIRECTORY }) }
        ui.onNodeWithTag("synthesis_text").performScrollTo().performTextReplacement("你好，欢迎使用本地语音。")
        ui.onNodeWithTag("advanced_settings").assertDoesNotExist()
        ui.onNodeWithTag("generate").performScrollTo().performClick()
        ui.waitUntil(300_000) { !vm.busy && vm.state.stage in setOf(GenerationStage.COMPLETED, GenerationStage.ERROR) }
        ui.runOnIdle {
            assertNull(vm.generationError)
            assertEquals(GenerationStage.COMPLETED, vm.state.stage)
            assertTrue(vm.output!!.durationMs > 0)
        }
        ui.onNodeWithTag("play_output").performScrollTo().performClick()
        ui.waitUntil(10_000) { vm.preview.playing }
        ui.onNodeWithTag("play_output").performClick()
    }
}
