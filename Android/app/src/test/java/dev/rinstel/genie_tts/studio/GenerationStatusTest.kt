package dev.rinstel.genie_tts.studio

import dev.rinstel.genie_tts.BackendServiceState
import dev.rinstel.genie_tts.inference.GenerationStage
import org.junit.Assert.*
import org.junit.Test

class GenerationStatusTest {
    @Test fun decoderLimitIsNotACompletionPercentage() {
        val status = generationStatus(BackendServiceState(progressLabel = "Decoder 96/1500", progressPercent = 40))
        assertEquals("已生成 96 个语音 token", status.detail)
        assertNull(status.percent)
    }

    @Test fun estimatedStagePercentIsNotDisplayed() {
        val status = generationStatus(BackendServiceState(stage = GenerationStage.PREPARING_FEATURES,
            progressLabel = "Text features", progressPercent = 24))
        assertEquals("正在解析文字", status.title)
        assertNull(status.percent)
        assertNull(status.detail)
    }

    @Test fun countedBytesShowOnlyTheCurrentFilePercentage() {
        val partial = generationStatus(BackendServiceState(progressLabel = "Unpacking weights 1048576/4194304"))
        assertEquals(25, partial.percent)
        assertEquals("当前文件 25%", partial.detail)
        assertEquals(100, generationStatus(BackendServiceState(progressLabel = "Unpacking weights 4194304/4194304")).percent)
        assertNull(generationStatus(BackendServiceState(progressLabel = "Unpacking weights 0/0")).percent)
    }
}
