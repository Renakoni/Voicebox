package dev.renakoni.voicebox.studio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun release(tag: String) = JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)

    @Test fun selectsNewestStableAppTagNumerically() {
        val releases = listOf(release("v1.1.3"), release("ming-runtime-v2"), release("v3.0.0").put("draft", true),
            release("v4.0.0").put("prerelease", true), release("v2.10.0"), release("v2.9.9"))
        assertEquals("v2.10.0", AppUpdate.latest(releases)?.getString("tag_name"))
        assertNull(AppUpdate.latest(releases.take(4)))
    }

    @Test fun readsTaggedVersionAndReleaseNotesAndRequiresTheOfficialAsset() {
        val name = "Voicebox-2.0.1-arm64.apk"
        val asset = JSONObject().put("name", name).put("size", 32).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/Renakoni/Voicebox/releases/download/v2.0.1/$name")
        val item = release("v2.0.1").put("body", "## Changes\n- Faster startup").put("assets", JSONArray().put(asset))
        val props = "voiceboxVersionCode=12\nvoiceboxVersionName=2.0.1"
        val update = AppUpdate.parse(item, props)
        assertEquals(12, update.code)
        assertEquals(item.getString("body"), update.notes)
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(item, props.replace("2.0.1", "2.0.0")) }
        asset.put("browser_download_url", "https://example.com/$name")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(item, props) }
    }
}
