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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val RELEASES = "https://github.com/Renakoni/Voicebox/releases"

internal data class AppUpdate(val version: String, val code: Int, val apk: ModelDownload) {
    companion object {
        fun fetch(installedCode: Int): AppUpdate? {
            val connection = URL("$RELEASES/latest/download/voicebox-update.json").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                check(connection.responseCode == HttpURLConnection.HTTP_OK)
                return parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, installedCode)
            } finally {
                connection.disconnect()
            }
        }

        fun parse(json: String, installedCode: Int): AppUpdate? {
            val data = JSONObject(json)
            val code = data.getInt("versionCode")
            val version = data.getString("versionName")
            val bytes = data.getLong("bytes")
            val sha256 = data.getString("sha256")
            require(code > 0 && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
            if (code <= installedCode) return null
            return AppUpdate(version, code,
                ModelDownload("$RELEASES/download/v$version/Voicebox-$version-arm64.apk", bytes, sha256))
        }
    }
}

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Failed(val reason: UiText) : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File, val version: String) : UpdateState
}

internal class AppUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    fun check() {
        if (state == UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        viewModelScope.launch {
            state = try {
                val update = withContext(Dispatchers.IO) { AppUpdate.fetch(BuildConfig.VERSION_CODE) }
                if (update == null) UpdateState.Current else UpdateState.Available(update)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateState.Failed(UiText(R.string.update_check_failed))
            }
        }
    }

    fun download() {
        val update = (state as? UpdateState.Available)?.update ?: return
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
                UpdateState.Ready(file, update.version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.userText() ?: UiText(R.string.download_failed))
            }
        }
    }
}
