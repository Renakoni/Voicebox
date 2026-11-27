package dev.renakoni.voicebox.core

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

internal class BridgeReleases(private val packages: List<BridgePackage>,
    private val listPublished: () -> List<JSONObject> = GitHubReleases::published,
    private val readCatalog: (String) -> String = GitHubReleases::text,
    private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var lastCheck: Long? = null
    private var releases = emptyList<JSONObject>()
    private val catalogs = mutableMapOf<Long, BridgePackageSpec>()

    @Synchronized fun refresh(gpuChanged: Boolean) {
        val now = clock()
        if (lastCheck?.let { now - it < 5 * 60_000 } == true) {
            if (!gpuChanged) return
        } else {
            try {
                releases = listPublished()
                lastCheck = now
            } catch (error: Exception) {
                Log.w("BridgeReleases", "Could not read published releases", error)
                if (!gpuChanged) return
            }
        }
        for (archive in packages) {
            archive.spec = latestCompatibleBridge(archive.bundled, releases, archive::accepts) { assetId, url ->
                // Replacing a GitHub release asset assigns it a new ID.
                catalogs.getOrPut(assetId) { BridgePackageSpec(JSONObject(readCatalog(url))) }
            } ?: archive.bundled
        }
    }
}

internal fun latestCompatibleBridge(base: BridgePackageSpec, releases: List<JSONObject>,
    accepts: (BridgePackageSpec) -> Boolean, readCatalog: (Long, String) -> BridgePackageSpec): BridgePackageSpec? {
    val prefix = "${base.id}-runtime-v"
    val candidates = releases.mapNotNull { release ->
        val tag = release.optString("tag_name")
        val version = tag.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
        if (tag == "$prefix$version" && version > base.version) version to release else null
    }.sortedByDescending { it.first }

    for ((version, release) in candidates) {
        val items = release.optJSONArray("assets") ?: continue
        val assets = (0 until items.length()).mapNotNull(items::optJSONObject).associateBy { it.optString("name") }
        val catalog = assets["${base.id}-runtime.json"] ?: assets["${base.id}.json"] ?: continue
        val directory = "${GitHubReleases.releasesUrl}/download/$prefix$version"
        val spec = try {
            readCatalog(catalog.getLong("id"), "$directory/${catalog.getString("name")}")
        } catch (error: Exception) {
            continue
        }
        if (spec.version != version || !accepts(spec)) continue
        if (spec.parts.all { part ->
            val name = part.url.substringAfterLast('/')
            part.url == "$directory/$name" && BridgePackageSpec.safeSegment(name) && assets[name]?.optLong("size") == part.bytes
        }) return spec
    }
    return null
}
