package dev.renakoni.voicebox.studio

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.ModelDownload
import dev.renakoni.voicebox.core.downloadModelFile
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import dev.renakoni.voicebox.core.i18n.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

private const val RELEASES = "https://github.com/Renakoni/Voicebox/releases"

internal data class AppUpdate(val version: String, val code: Int, val notes: String, val apk: ModelDownload) {
    val releaseUrl get() = "$RELEASES/tag/v$version"

    companion object {
        fun fetch(): AppUpdate? {
            val releases = mutableListOf<JSONObject>()
            var page = 1
            do {
                val batch = JSONArray(get("https://api.github.com/repos/Renakoni/Voicebox/releases?per_page=100&page=$page"))
                for (i in 0 until batch.length()) releases += batch.getJSONObject(i)
                page++
            } while (batch.length() == 100)
            val release = latest(releases) ?: return null
            val tag = release.getString("tag_name")
            val versions = get("https://raw.githubusercontent.com/Renakoni/Voicebox/refs/tags/$tag/Android/gradle.properties")
            return parse(release, versions)
        }

        private fun get(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "VoiceBox/${BuildConfig.VERSION_NAME}")
                check(connection.responseCode == HttpURLConnection.HTTP_OK)
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        fun latest(releases: List<JSONObject>): JSONObject? {
            val tag = Regex("v([0-9]+)\\.([0-9]+)\\.([0-9]+)")
            return releases.mapNotNull { item ->
                if (item.getBoolean("draft") || item.getBoolean("prerelease")) return@mapNotNull null
                val match = tag.matchEntire(item.getString("tag_name")) ?: return@mapNotNull null
                val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return@mapNotNull null }
                if (parts[0] < 2) null else item to parts
            }.maxWithOrNull(compareBy({ it.second[0] }, { it.second[1] }, { it.second[2] }))?.first
        }

        fun parse(release: JSONObject, versions: String): AppUpdate {
            val properties = Properties().apply { versions.reader().use(::load) }
            val code = properties.getProperty("voiceboxVersionCode").toInt()
            val version = properties.getProperty("voiceboxVersionName")
            val tag = release.getString("tag_name")
            require(code > 0 && tag == "v$version")
            val assets = release.getJSONArray("assets")
            val name = "Voicebox-$version-arm64.apk"
            val asset = (0 until assets.length()).map(assets::getJSONObject).single { it.getString("name") == name }
            val url = "$RELEASES/download/$tag/$name"
            val digest = asset.getString("digest")
            val bytes = asset.getLong("size")
            require(asset.getString("browser_download_url") == url && bytes > 0 && digest.matches(Regex("sha256:[0-9a-f]{64}")))
            return AppUpdate(version, code, release.optString("body").trim(), ModelDownload(url, bytes, digest.substringAfter(':')))
        }
    }
}

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Failed(val reason: UiText) : UpdateState
    data object Available : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File) : UpdateState
}

internal class AppUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    var release by mutableStateOf<AppUpdate?>(null)
        private set
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    fun check() {
        if (state == UpdateState.Checking || state is UpdateState.Downloading) return
        release = null
        state = UpdateState.Checking
        viewModelScope.launch {
            state = try {
                val update = withContext(Dispatchers.IO) { AppUpdate.fetch() }
                release = update
                if (update == null || update.code <= BuildConfig.VERSION_CODE) UpdateState.Current else UpdateState.Available
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateState.Failed(UiText(R.string.update_check_failed))
            }
        }
    }

    fun download() {
        val update = release ?: return
        if (update.code <= BuildConfig.VERSION_CODE || state is UpdateState.Downloading || state == UpdateState.Checking) return
        state = UpdateState.Downloading(0)
        viewModelScope.launch {
            state = try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "app-updates").apply { mkdirs() }
                    val part = File.createTempFile("download-", ".apk", directory)
                    val apk = File(directory, "Voicebox-${update.code}.apk")
                    try {
                        downloadModelFile(update.apk, part, { ensureActive() }) { state = UpdateState.Downloading(it) }
                        @Suppress("DEPRECATION")
                        val info = app.packageManager.getPackageArchiveInfo(part.path, 0)
                        requireUi(info != null && info.packageName == app.packageName &&
                            info.longVersionCode == update.code.toLong() && info.versionName == update.version) {
                            UiText(R.string.update_package_mismatch)
                        }
                        Files.move(part.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        apk
                    } finally {
                        part.delete()
                    }
                }
                UpdateState.Ready(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.userText() ?: UiText(R.string.download_failed))
            }
        }
    }
}
