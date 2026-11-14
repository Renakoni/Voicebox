package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceLanguageTest {
    @Test fun oldPackagesUseTranscriptScriptAndExplicitChoiceWins() {
        assertEquals("japanese", ReferenceLanguage.resolve("auto", "えっと、ありがとうございました"))
        assertEquals("chinese", ReferenceLanguage.resolve("auto", "今晚风很轻。"))
        assertEquals("english", ReferenceLanguage.resolve("auto", "Hello, this is a test."))
        assertEquals("japanese", ReferenceLanguage.resolve("ja", "東京"))
        assertEquals("chinese", ReferenceLanguage.resolve("zh", "かな"))
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
