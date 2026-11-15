package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

        assertEquals(listOf("n", "i2", "h", "ao3", "EY1", "B", "IY1", "S", "IY1"), features.symbols)
        assertEquals(9, features.phoneIds.values.size)
        assertEquals(9L, features.bert.shape[0])
    }

    @Test
    fun supportsExplicitHybridLanguage() {
        val chinese = ChineseTextFeatureExtractor(
            ChineseG2pResources(
                polyphonicPinyin = emptyMap(),
                singleCharPinyin = mapOf("\u4f60" to "ni3"),
                opencpopByPinyin = mapOf("ni" to "n i"),
            ),
        )
        val extractor = DefaultTextFeatureExtractor(
            chinese = chinese,
            english = EnglishTextFeatureExtractor { text -> if (text == "ABC") listOf("EY1", "B", "IY1", "S", "IY1") else listOf("AH0") },
            fallback = UnsupportedLanguageTextFeatureExtractor(),
        )

        val features = extractor.extract("hybrid-chinese-english", "\u4f60A")

        assertEquals(listOf("n", "i3", "AH0"), features.symbols)
    }

    @Test
    fun usesChineseExtractorForChineseRequests() {
        val extractor = DefaultTextFeatureExtractor(
            chinese = ChineseTextFeatureExtractor(
                ChineseG2pResources(
                    polyphonicPinyin = mapOf("哪吒" to listOf("ne2", "zha1")),
                    opencpopByPinyin = mapOf("ne" to "n e", "zha" to "zh a"),
                    singleCharPinyin = emptyMap(),
                ),
            ),
            fallback = UnsupportedLanguageTextFeatureExtractor(),
        )

        val features = extractor.extract("zh", "哪吒")

        assertEquals(4, features.phoneIds.values.size)
        assertTrue(features.symbols.contains("zh"))
    }

}
