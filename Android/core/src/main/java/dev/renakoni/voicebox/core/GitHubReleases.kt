package dev.renakoni.voicebox.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHubReleases {
    private const val repository = "Renakoni/Voicebox"
    const val projectUrl = "https://github.com/$repository"
    const val releasesUrl = "$projectUrl/releases"

    internal fun published(): List<JSONObject> = buildList {
        var page = 1
        do {
            val items = JSONArray(text("https://api.github.com/repos/$repository/releases?per_page=50&page=${page++}"))
            for (i in 0 until items.length()) {
                val release = items.getJSONObject(i)
                if (!release.optBoolean("draft") && !release.optBoolean("prerelease")) add(release)
            }
        } while (items.length() == 50)
    }

    fun latestTag(): String? = request("$releasesUrl/latest", "HEAD", followRedirects = false) { connection ->
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_NOT_FOUND) return@request null
        check(status in 300..399) { "HTTP $status from ${connection.url.host}" }
        val releaseUrl = URL(connection.url, checkNotNull(connection.getHeaderField("Location"))).toExternalForm()
        if (releaseUrl == releasesUrl) null else {
            val prefix = "$releasesUrl/tag/"
            check(releaseUrl.startsWith(prefix)) { "Unexpected latest release URL" }
            releaseUrl.removePrefix(prefix)
        }
    }

    fun size(url: String): Long = request(url, "HEAD") {
        checkResponse(it)
        it.contentLengthLong.also { length -> check(length > 0) { "Missing download size" } }
    }

    fun text(url: String): String = request(url) {
        checkResponse(it)
        it.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    }

    private fun checkResponse(connection: HttpURLConnection) {
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode} from ${connection.url.host}" }
    }

    private fun <T> request(url: String, method: String = "GET", followRedirects: Boolean = true,
        read: (HttpURLConnection) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.requestMethod = method
            connection.instanceFollowRedirects = followRedirects
            connection.setRequestProperty("User-Agent", "VoiceBox/${BuildConfig.VERSION_NAME}")
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }
}
