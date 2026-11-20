package dev.renakoni.voicebox.model.gptsovits

import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SemanticTokenSlicingTest {
    @Test fun respectsCompletedCountAndExcludesSpecialTokens() {
        val cases = listOf(
            Triple(longArrayOf(99, 1024), 1, longArrayOf()),
            Triple(longArrayOf(99, 11, 12, 13), 3, longArrayOf(11, 12, 13)),
            Triple(longArrayOf(99, 21, 22, 1024), 3, longArrayOf(21, 22)),
            Triple(longArrayOf(99, 31, 32, 0), 3, longArrayOf(31, 32)),
            Triple(longArrayOf(99, 41, 1024, 0), 3, longArrayOf(41)),
            Triple(longArrayOf(99, 31, 32), 0, longArrayOf()),
        )
        cases.forEach { (tokens, count, expected) ->
            assertArrayEquals(tokens.contentToString(), expected, SemanticTokenSlicing.completedTokens(tokens, count))
        }
    }

    @Test
    fun emptySemanticCannotBecomePlaceholderSuccessAndRetriesAreBounded() {
        assertThrows(EmptySemanticException::class.java) { SemanticTokenStability.requireNonEmpty(longArrayOf()) }
        assertArrayEquals(longArrayOf(2), SemanticTokenStability.requireNonEmpty(longArrayOf(2)))
        assertTrue(SemanticTokenStability.shouldRetry(0, 30, 0))
        assertTrue(SemanticTokenStability.shouldRetry(0, 30, 1))
        assertFalse(SemanticTokenStability.shouldRetry(0, 30, 2))
    }
}
