package dev.renakoni.voicebox.studio

import android.app.Application
import android.util.Log
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.GitHubReleases
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
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal const val APP_RELEASES_URL = GitHubReleases.releasesUrl

internal data class AppUpdate(val version: String, val code: Int, val notesHtml: String?, val apk: ModelDownload) {
    val releaseUrl get() = "$APP_RELEASES_URL/tag/v$version"

    companion object {
        fun fetch(): AppUpdate? {
            val tag = GitHubReleases.latestTag() ?: return null
            val candidate = releaseVersion(tag) ?: return null
            val installed = checkNotNull(releaseVersion("v${BuildConfig.VERSION_NAME}"))
            if (compareValuesBy(candidate, installed, { it.first }, { it.second }, { it.third }) <= 0) return null

            val versions = GitHubReleases.text("https://raw.githubusercontent.com/Renakoni/Voicebox/refs/tags/$tag/Android/gradle.properties")
            val properties = Properties().apply { versions.reader().use(::load) }
            val code = properties.getProperty("voiceboxVersionCode").toInt()
            val version = properties.getProperty("voiceboxVersionName")
            require(code > 0 && tag == "v$version")
            if (code <= BuildConfig.VERSION_CODE) return null

            val name = "Voicebox-$version-arm64.apk"
            val assetBase = "$APP_RELEASES_URL/download/$tag"
            val url = "$assetBase/$name"
            val checksums = GitHubReleases.text("$assetBase/SHA256SUMS.txt")
            val checksum = Regex("([0-9a-fA-F]{64})[ \\t]+\\*?${Regex.escape(name)}")
            val digest = checksums.lineSequence().mapNotNull { checksum.matchEntire(it.trim()) }
                .single().groupValues[1].lowercase()
            val bytes = GitHubReleases.size(url)
            val notes = try {
                releaseNotes(tag)
            } catch (e: Exception) {
                Log.w("VoiceBoxUpdate", "Could not load release notes", e)
                null
            }
            return AppUpdate(version, code, notes, ModelDownload(url, bytes, digest))
        }

        private fun releaseVersion(tag: String): Triple<Int, Int, Int>? {
            val parts = Regex("v([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(tag)?.groupValues ?: return null
            return Triple(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null)
        }

        private fun releaseNotes(tag: String): String? {
            val parser = Xml.newPullParser().apply { setInput(GitHubReleases.text("$APP_RELEASES_URL.atom").reader()) }
            var matchingEntry = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "entry" -> matchingEntry = false
                    "id" -> matchingEntry = parser.nextText().substringAfterLast('/') == tag
                    "content" -> if (matchingEntry) return parser.nextText().trim()
                }
            }
            return null
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

    fun checkOnLaunch() {
        if (state == UpdateState.Idle) check()
    }

    fun check() {
        if (state == UpdateState.Checking || state is UpdateState.Downloading) return
        release = null
        state = UpdateState.Checking
        viewModelScope.launch {
            state = try {
                val update = withContext(Dispatchers.IO) { AppUpdate.fetch() }
                release = update
                if (update == null) UpdateState.Current else UpdateState.Available
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VoiceBoxUpdate", "Update check failed", e)
                UpdateState.Failed(e.userText() ?: UiText(R.string.update_check_failed))
            }
        }
    }

    fun download() {
        val update = release ?: return
        if (state != UpdateState.Available && state !is UpdateState.Failed) return
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
