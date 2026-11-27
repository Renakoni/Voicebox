package dev.renakoni.voicebox.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BridgeReleaseSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun catalog(version: Int, contract: Int, minAppVersionCode: Int = 1): JSONObject {
        val url = "${GitHubReleases.releasesUrl}/download/qwen3-runtime-v$version/runtime.zip"
        return JSONObject().put("id", "qwen3").put("version", version).put("contract", contract)
            .put("minAppVersionCode", minAppVersionCode)
            .put("format", 2).put("abi", "arm64-v8a").put("entry", "example.Entry").put("code", "tasks.jar")
            .put("parts", JSONArray().put(JSONObject().put("url", url).put("bytes", 100)))
            .put("files", JSONObject().put("bridge.json", 10).put("tasks.jar", 20))
    }

    private fun offer(vararg catalogs: JSONObject, base: BridgePackageSpec = BridgePackageSpec(catalog(6, 1)),
        appVersion: Long = 13, onRead: (String) -> Unit = {}): BridgePackageSpec? {
        val byUrl = mutableMapOf<String, String>()
        val releases = catalogs.map { json ->
            val tag = "qwen3-runtime-v${json.getInt("version")}"
            byUrl["${GitHubReleases.releasesUrl}/download/$tag/qwen3-runtime.json"] = json.toString()
            JSONObject().put("tag_name", tag).put("assets", JSONArray()
                .put(JSONObject().put("name", "qwen3-runtime.json")).put(JSONObject().put("name", "runtime.zip")))
        }
        // Only metadata URLs exist in this reader. Trying to fetch a ZIP fails the test.
        return latestCompatibleBridge(base, releases, { it.matchesHost(base, "arm64-v8a", 34, appVersion) }) { url ->
            onRead(url)
            byUrl[url] ?: throw AssertionError("Unexpected download $url")
        }
    }

    @Test fun offersNewestCompatibleBridgeBeforeDownloading() {
        assertEquals(8, offer(catalog(8, 1), catalog(9, 2))?.version)
        assertEquals(9, offer(catalog(8, 1), catalog(9, 1))?.version)
    }

    @Test fun upgradingTheAppUnlocksOneDirectBridgeInstall() {
        val v6 = BridgePackageSpec(catalog(6, 1))
        val root = folder.newFolder("qwen3")
        val old = File(root, "v6/installed").apply { mkdirs() }
        File(old, "tasks.jar").writeText("v6")
        File(old, "bridge.json").writeText(v6.json.toString())
        // Receipts from before remote discovery have no numeric version.
        File(root, "active.json").writeText("""{"revision":"v6","directory":"installed"}""")
        val catalogs = (7..9).map { catalog(it, 1, minAppVersionCode = 14) }.toTypedArray()
        val newest = catalogs.last()
        val imports = folder.newFolder("imports")
        val zip = File(imports, "qwen3-runtime-v9/runtime.zip").apply { parentFile!!.mkdirs() }
        val manifest = JSONObject(newest.toString()).apply { remove("files"); remove("parts") }.toString()
        val files = mapOf("bridge.json" to manifest.toByteArray(), "tasks.jar" to "v9".toByteArray())
        ZipOutputStream(zip.outputStream()).use { output ->
            for ((name, bytes) in files) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        newest.put("files", JSONObject(files.mapValues { it.value.size }))
        newest.getJSONArray("parts").getJSONObject(0).put("bytes", zip.length())

        fun host(version: Long) = BridgePackage(root, v6, "arm64-v8a", version, imports) { true }
        assertNull(offer(*catalogs, appVersion = 13))
        assertEquals(BridgeInstallState.CURRENT, host(13).installState)
        val requests = mutableListOf<String>()
        val upgraded = host(14)
        upgraded.spec = requireNotNull(offer(*catalogs, appVersion = 14, onRead = requests::add))
        assertEquals(listOf("${GitHubReleases.releasesUrl}/download/qwen3-runtime-v9/qwen3-runtime.json"), requests)
        assertEquals(BridgeInstallState.UPDATE_AVAILABLE, upgraded.installState)
        assertEquals("v6", upgraded.active?.revision)
        assertTrue(upgraded.install({}, {}))
        try {
            assertEquals("v9", upgraded.active?.revision)
            assertFalse(old.exists())
            // A new process using only its bundled v6 catalog keeps the installed v9.
            assertEquals(BridgeInstallState.CURRENT, host(14).installState)
            assertFalse(host(14).install({}, {}))
            assertNull(host(13).active)
        } finally {
            upgraded.active?.let { File(it.directory, it.code).setWritable(true) }
        }
    }

    @Test fun appMustImplementTheNewContractToSeeItsReleases() {
        val v9 = catalog(9, 2, minAppVersionCode = 14)
        assertNull(offer(v9, appVersion = 14))
        assertEquals(9, offer(v9, base = BridgePackageSpec(catalog(7, 2)), appVersion = 14)?.version)
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
