package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class V5PreparationTest {
    @Test fun preparesOnlyFullAndTailShapesIncludingExactBoundaries() {
        val cases = listOf(
            80 to listOf(512),
            81 to listOf(768),
            336 to listOf(768),
            337 to listOf(1000),
            568 to listOf(1000),
            569 to listOf(1000, 512),
            904 to listOf(1000, 768),
            1136 to listOf(1000),
            1137 to listOf(1000, 512),
        )
        cases.forEach { (frames, expected) ->
            assertEquals("target frames=$frames", expected, V5DiTRuntime.requiredBuckets(432, frames))
        }
        assertEquals(listOf(1000), V5DiTRuntime.requiredBuckets(300, 1280))
        assertEquals(listOf(1000, 512), V5DiTRuntime.requiredBuckets(300, 1281))
    }
}
