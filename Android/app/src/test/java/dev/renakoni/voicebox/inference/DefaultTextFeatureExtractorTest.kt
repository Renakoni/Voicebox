package dev.renakoni.voicebox.inference

import org.junit.Assert.assertEquals
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


}
