package dev.renakoni.voicebox.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BridgeReleaseSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun catalog(version: Int, contract: Int, minAppVersionCode: Int = 1): JSONObject {
        val url = "${GitHubReleases.releasesUrl}/download/qwen3-runtime-v$version/runtime-v$version-arm64.zip"
        return JSONObject().put("id", "qwen3").put("version", version).put("contract", contract)
            .put("minAppVersionCode", minAppVersionCode)
            .put("format", 2).put("abi", "arm64-v8a").put("entry", "example.Entry").put("code", "tasks.jar")
            .put("parts", JSONArray().put(JSONObject().put("url", url).put("bytes", 100)))
            .put("files", JSONObject().put("bridge.json", 10).put("tasks.jar", 20))
    }

    private fun release(json: JSONObject, catalogId: Long = json.getLong("version")) =
        JSONObject().put("tag_name", "qwen3-runtime-v${json.getInt("version")}").put("assets", JSONArray()
            .put(JSONObject().put("name", "qwen3-runtime.json").put("id", catalogId))
            .put(JSONObject().put("name", "runtime-v${json.getInt("version")}-arm64.zip")
                .put("size", json.getJSONArray("parts").getJSONObject(0).getLong("bytes"))))

    private fun offer(vararg catalogs: JSONObject, base: BridgePackageSpec = BridgePackageSpec(catalog(6, 1)),
        appVersion: Long = 13, onRead: (String) -> Unit = {}): BridgePackageSpec? {
        val byUrl = mutableMapOf<String, String>()
        val releases = catalogs.map { json ->
            val tag = "qwen3-runtime-v${json.getInt("version")}"
            byUrl["${GitHubReleases.releasesUrl}/download/$tag/qwen3-runtime.json"] = json.toString()
            release(json)
        }
        // Only metadata URLs exist in this reader. Trying to fetch a ZIP fails the test.
        return latestCompatibleBridge(base, releases, { it.matchesHost(base, "arm64-v8a", 34, appVersion) }) { _, url ->
            onRead(url)
            BridgePackageSpec(JSONObject(byUrl[url] ?: throw AssertionError("Unexpected download $url")))
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
        newest.put("requiresVulkan12Storage16", true)
        val imports = folder.newFolder("imports")
        val zipName = "runtime-v9-arm64.zip"
        File(imports, "qwen3-runtime-v9/$zipName").apply { parentFile!!.mkdirs(); writeText("incomplete") }
        val zip = File(imports, zipName)
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

        fun host(version: Long, storage16: Boolean = true) =
            BridgePackage(root, v6, "arm64-v8a", version, imports) { !it || storage16 }
        assertNull(offer(*catalogs, appVersion = 13))
        assertEquals(BridgeInstallState.CURRENT, host(13).installState)
        val requests = mutableListOf<String>()
        val upgraded = host(14)
        upgraded.spec = requireNotNull(offer(*catalogs, appVersion = 14, onRead = requests::add))
        assertEquals(listOf("${GitHubReleases.releasesUrl}/download/qwen3-runtime-v9/qwen3-runtime.json"), requests)
        assertEquals(BridgeInstallState.UPDATE_AVAILABLE, upgraded.installState)
        assertEquals("v6", upgraded.active?.revision)
        var cancel = false
        assertThrows(CancellationException::class.java) {
            upgraded.install({ if (cancel) throw CancellationException() }, { cancel = true })
        }
        assertEquals("v6", upgraded.active?.revision)
        assertTrue(upgraded.install({}, {}))
        try {
            assertEquals("v9", upgraded.active?.revision)
            assertFalse(old.exists())
            // A new process using only its bundled v6 catalog keeps the installed v9.
            assertEquals(BridgeInstallState.CURRENT, host(14).installState)
            assertFalse(host(14).install({}, {}))
            assertNull(host(13).active)
            val gpuChanged = host(14, storage16 = false)
            assertNull(gpuChanged.active)
            assertEquals(BridgeInstallState.UPDATE_REQUIRED, gpuChanged.installState)
        } finally {
            upgraded.active?.let { File(it.directory, it.code).setWritable(true) }
        }
    }

    @Test fun offlineHostKeepsACompatibleBridgeWhenBundledNeedsANewerGpu() {
        val base = BridgePackageSpec(catalog(6, 1).put("requiresVulkan12Storage16", true))
        val root = folder.newFolder("qwen3")
        val installed = File(root, "v9/installed").apply { mkdirs() }
        File(installed, "tasks.jar").writeText("v9")
        File(installed, "bridge.json").writeText(catalog(9, 1).toString())
        File(root, "active.json").writeText("""{"revision":"v9","directory":"installed","version":9,"requiresVulkan12Storage16":false}""")
        val offline = BridgePackage(root, base, "arm64-v8a", 14) { !it }
        assertEquals("v9", offline.active?.revision)
        assertEquals(BridgeInstallState.CURRENT, offline.installState)
        assertFalse(offline.install({}, {}))
    }

    @Test fun appMustImplementTheNewContractToSeeItsReleases() {
        val v9 = catalog(9, 2, minAppVersionCode = 14)
        assertNull(offer(v9, appVersion = 14))
        assertEquals(9, offer(v9, base = BridgePackageSpec(catalog(7, 2)), appVersion = 14)?.version)
    }

    @Test fun gpuRecheckReusesMetadataAndRefreshPicksUpReplacedOrWithdrawnAssets() {
        val base = BridgePackageSpec(catalog(6, 1))
        val v9 = catalog(9, 1).put("requiresVulkan12Storage16", true)
        var storage16 = true
        val archive = BridgePackage(folder.root, base, base.abi, 14) { !it || storage16 }
        var published = listOf(release(v9))
        var now = 0L
        var lists = 0
        var reads = 0
        val releases = BridgeReleases(listOf(archive), { lists++; published }, { reads++; v9.toString() }, { now })
        releases.refresh(false)
        assertEquals(9, archive.spec.version)
        storage16 = false
        releases.refresh(true)
        assertEquals(6, archive.spec.version)
        storage16 = true
        releases.refresh(true)
        releases.refresh(false)
        assertEquals(9, archive.spec.version)
        assertEquals(1, lists)
        assertEquals(1, reads)

        now += 300_000
        published.single().getJSONArray("assets").getJSONObject(1).put("size", 10)
        releases.refresh(false)
        assertEquals(6, archive.spec.version)
        assertEquals(1, reads)
        now += 300_000
        v9.put("displayName", "Updated catalog")
        published = listOf(release(v9, catalogId = 99))
        releases.refresh(false)
        assertEquals("Updated catalog", archive.spec.displayName)
        assertEquals(2, reads)
        now += 300_000
        published = emptyList()
        releases.refresh(false)
        assertEquals(6, archive.spec.version)
        assertEquals(2, reads)
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
