package dev.renakoni.voicebox.model.gptsovits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultTextFeatureExtractorTest {
    @Test
    fun autoDetectsHybridFrontendForChineseTextContainingEnglish() {
        val chinese = ChineseTextFeatureExtractor(
            ChineseG2pResources(
                polyphonicPinyin = emptyMap(),
                singleCharPinyin = mapOf(
                    "\u4f60" to "ni3",
                    "\u597d" to "hao3",
                ),
                opencpopByPinyin = mapOf(
                    "ni" to "n i",
                    "hao" to "h ao",
                ),
            ),
        )
        val extractor = DefaultTextFeatureExtractor(
            chinese = chinese,
            english = EnglishTextFeatureExtractor { text -> if (text == "ABC") listOf("EY1", "B", "IY1", "S", "IY1") else listOf("AH0") },
            fallback = UnsupportedLanguageTextFeatureExtractor(),
        )

        val features = extractor.extract("zh", "\u4f60\u597dABC")
        assertEquals(features.symbols, extractor.extract("hybrid-chinese-english", "\u4f60\u597dABC").symbols)

        assertEquals(listOf("n", "i2", "h", "ao3", "EY1", "B", "IY1", "S", "IY1"), features.symbols)
        assertEquals(9, features.phoneIds.values.size)
        assertEquals(9L, features.bert.shape[0])
    }



    @Test fun englishNumbersKeepMagnitudeAndOrdinalEndings() {
        assertEquals("twelve", EnglishNumbers.cardinal(12))
        assertEquals("two thousand twenty six", EnglishNumbers.cardinal(2026))
        assertEquals("one million one", EnglishNumbers.cardinal(1_000_001))
        assertEquals("twenty first", EnglishNumbers.ordinal(21))
        assertEquals("twelfth", EnglishNumbers.ordinal(12))
    }
    @Test fun wrongLanguageDoesNotSilentlyDiscardJapaneseOrChineseCharacters() {
        assertNotNull(LanguageNormalizer.scriptError("zh", "今夜は静かです。"))
        assertNotNull(LanguageNormalizer.scriptError("en", "今晚风很轻。"))
        assertNull(LanguageNormalizer.scriptError("zh", "你好 Genie"))
        assertNull(LanguageNormalizer.scriptError("ja", "今夜は静かです。"))
    }
}
