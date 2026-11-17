package dev.rinstel.genie_tts.inference

import org.junit.Assert.*
import org.junit.Test

class SynthesisOptionsTest {
    @Test fun validatesBoundariesAndRejectsInvalidNumbers() {
        assertEquals(SynthesisOptions.Default, SynthesisOptions.parse(emptyMap()))
        SynthesisOptions(0.6, 0f, 1, 0f, 1f)
        SynthesisOptions(1.65, 1f, 100, 1f, 2f)
        for ((key, values) in mapOf(
            "speed" to listOf("0.59", "1.66", "NaN", "Infinity", "bad"),
            "temperature" to listOf("-0.01", "1.01", "NaN", "Infinity"),
            "topK" to listOf("0", "101", "1.5"),
            "topP" to listOf("-0.01", "1.01", "NaN", "Infinity"),
            "repetitionPenalty" to listOf("0.99", "2.01", "NaN", "Infinity"),
        )) for (value in values + listOf(null)) {
            assertThrows("$key=$value", IllegalArgumentException::class.java) { SynthesisOptions.parse(mapOf(key to value)) }
        }
    }
}
