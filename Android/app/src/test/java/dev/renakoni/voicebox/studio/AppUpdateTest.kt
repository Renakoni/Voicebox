package dev.renakoni.voicebox.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateTest {
    private val digest = "0".repeat(64)
    private val manifest = """{"versionName":"2.0.1","versionCode":12,"bytes":300,"sha256":"$digest"}"""

    @Test fun onlyHigherAndroidVersionCodesOfferAnUpdate() {
        assertNull(AppUpdate.parse(manifest, 12))
        assertNull(AppUpdate.parse(manifest, 13))
        val update = requireNotNull(AppUpdate.parse(manifest, 11))
        assertEquals("https://github.com/Renakoni/Voicebox/releases/download/v2.0.1/Voicebox-2.0.1-arm64.apk", update.apk.url)
        assertEquals(digest, update.apk.sha256)
    }

    @Test fun malformedReleaseCannotBecomeAnInstall() {
        for (invalid in listOf(manifest.replace(digest, "bad"), manifest.replace("2.0.1", "../asset"))) {
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(invalid, 11) }
        }
    }
}
