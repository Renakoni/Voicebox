package dev.renakoni.voicebox.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeReleaseSelectionTest {
    private fun catalog(version: Int, contract: Int): JSONObject {
        val url = "${GitHubReleases.releasesUrl}/download/qwen3-runtime-v$version/runtime.zip"
        return JSONObject().put("id", "qwen3").put("version", version).put("contract", contract)
            .put("format", 2).put("abi", "arm64-v8a").put("entry", "example.Entry").put("code", "tasks.jar")
            .put("parts", JSONArray().put(JSONObject().put("url", url).put("bytes", 100)))
            .put("files", JSONObject().put("bridge.json", 10).put("tasks.jar", 20))
    }

    private fun offer(vararg catalogs: JSONObject): BridgePackageSpec? {
        val base = BridgePackageSpec(catalog(6, 1))
        val byUrl = mutableMapOf<String, String>()
        val releases = catalogs.map { json ->
            val tag = "qwen3-runtime-v${json.getInt("version")}"
            byUrl["${GitHubReleases.releasesUrl}/download/$tag/qwen3-runtime.json"] = json.toString()
            JSONObject().put("tag_name", tag).put("assets", JSONArray()
                .put(JSONObject().put("name", "qwen3-runtime.json")).put(JSONObject().put("name", "runtime.zip")))
        }
        // Only metadata URLs exist in this reader. Trying to fetch a ZIP fails the test.
        return latestCompatibleBridge(base, releases, { it.matchesHost(base, "arm64-v8a", 34, 13) }) { url ->
            byUrl[url] ?: throw AssertionError("Unexpected download $url")
        }
    }

    @Test fun offersNewestCompatibleBridgeBeforeDownloading() {
        assertEquals(8, offer(catalog(8, 1), catalog(9, 2))?.version)
        assertEquals(9, offer(catalog(8, 1), catalog(9, 1))?.version)
    }

    @Test fun incompatibleOrUnavailablePackagesProduceNoOffer() {
        assertNull(offer(catalog(9, 2), catalog(8, 1).put("minAppVersionCode", 14),
            catalog(7, 1).put("minSdk", 35), catalog(6, 1)))
        assertNull(offer(catalog(9, 1).put("id", "ming"), catalog(8, 1).put("abi", "x86_64")))
        val missing = catalog(9, 1)
        missing.getJSONArray("parts").getJSONObject(0).put("url", "https://example.com/runtime.zip")
        assertNull(offer(missing))
    }
}
