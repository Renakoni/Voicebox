package dev.renakoni.voicebox.model.gptsovits

import org.junit.Assert.*
import org.junit.Test

class SynthesisOptionsTest {
    @Test fun validatesDecoderLimitAndUnknownRequestOptions() {
        for (options in listOf(mapOf("maxDecoderSteps" to "0"), mapOf("maxDecoderSteps" to "-1"), mapOf("unknown" to "1"))) {
            assertThrows(IllegalArgumentException::class.java) { SynthesisOptions.validateRequestOptions(options) }
        }
        assertEquals("321", SynthesisOptions.validateRequestOptions(mapOf("maxDecoderSteps" to "321"))["maxDecoderSteps"])
    }

    @Test fun validatesBoundariesAndRejectsInvalidNumbers() {
        SynthesisOptions(0.6, 0f, 1, 0f, 1f)
        SynthesisOptions(1.65, 1f, 100, 1f, 2f)
        for ((key, values) in mapOf(
            "speed" to listOf("0.59", "1.66", "NaN", "Infinity", "bad"),
            "temperature" to listOf("-0.01", "1.01", null),
            "topK" to listOf("0", "101", "1.5"),
            "topP" to listOf("-0.01", "1.01"),
            "repetitionPenalty" to listOf("0.99", "2.01"),
        )) for (value in values) {
            assertThrows("$key=$value", IllegalArgumentException::class.java) { SynthesisOptions.parse(mapOf(key to value)) }
        }
    }
}
