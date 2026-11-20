package dev.renakoni.voicebox.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceLanguageTest {
    @Test fun autoLanguageUsesTranscriptScriptAndExplicitChoiceWins() {
        assertEquals("japanese", ReferenceLanguage.resolve("auto", "えっと、ありがとうございました"))
        assertEquals("chinese", ReferenceLanguage.resolve("auto", "今晚风很轻。"))
        assertEquals("english", ReferenceLanguage.resolve("auto", "Hello, this is a test."))
        assertEquals("japanese", ReferenceLanguage.resolve("ja", "東京"))
        assertEquals("chinese", ReferenceLanguage.resolve("zh", "かな"))
    }
}
