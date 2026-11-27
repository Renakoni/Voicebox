package dev.renakoni.voicebox.core

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

internal class BridgeReleases(private val packages: List<BridgePackage>) {
    private var lastCheck: Long? = null

    @Synchronized fun refresh(gpuChanged: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!gpuChanged && lastCheck?.let { now - it < 5 * 60_000 } == true) return
        val releases = try {
            GitHubReleases.published()
        } catch (error: Exception) {
            Log.w("BridgeReleases", "Could not read published releases", error)
            return
        }
        lastCheck = now
        for (archive in packages) {
            val selected = latestCompatibleBridge(archive.bundled, releases, archive::accepts, GitHubReleases::text)
                ?: archive.bundled
            if (archive.spec.json.toString() != selected.json.toString()) archive.spec = selected
        }
    }
}

internal fun latestCompatibleBridge(base: BridgePackageSpec, releases: List<JSONObject>,
    accepts: (BridgePackageSpec) -> Boolean, readCatalog: (String) -> String): BridgePackageSpec? {
    val prefix = "${base.id}-runtime-v"
    val candidates = releases.mapNotNull { release ->
        val tag = release.optString("tag_name")
        val version = tag.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
        if (tag == "$prefix$version" && version > base.version) version to release else null
    }.sortedByDescending { it.first }

    for ((version, release) in candidates) {
        val assets = release.optJSONArray("assets") ?: continue
        val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }
        val catalog = listOf("${base.id}-runtime.json", "${base.id}.json").firstOrNull { it in names } ?: continue
        val directory = "${GitHubReleases.releasesUrl}/download/$prefix$version"
        val spec = try {
            BridgePackageSpec(JSONObject(readCatalog("$directory/$catalog")))
        } catch (error: Exception) {
            continue
        }
        if (spec.version != version || !accepts(spec)) continue
        val urls = names.filter(BridgePackageSpec::safeSegment).map { "$directory/$it" }
        if (spec.parts.all { it.url in urls }) return spec
    }
    return null
}
