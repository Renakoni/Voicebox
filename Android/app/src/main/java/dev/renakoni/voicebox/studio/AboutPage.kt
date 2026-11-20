@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renakoni.voicebox.studio

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.renakoni.voicebox.core.i18n.UiText
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.R

@Composable
internal fun AboutPage(onBack: () -> Unit, onError: (UiText) -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure { onError(UiText(R.string.link_open_failed)) }
    }
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text(resources.getString(R.string.about), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_settings)) } },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.matchParentSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), resources.getString(R.string.app_icon), Modifier.matchParentSize())
                }
                Text("VoiceBox", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text(resources.getString(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(resources.getString(R.string.app_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AppUpdateRow(onError)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AboutAction("GitHub", "github.com/Renakoni/Voicebox") { openLink("https://github.com/Renakoni/Voicebox") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AboutAction(resources.getString(R.string.report_issue), "GitHub Issues") { openLink("https://github.com/Renakoni/Voicebox/issues") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AboutAction(resources.getString(R.string.open_source_license), "AGPL-3.0") { openLink("https://github.com/Renakoni/Voicebox/blob/main/LICENSE") }
        }
    }
}

@Composable
private fun AppUpdateRow(onError: (UiText) -> Unit) {
    val vm: AppUpdateViewModel = viewModel()
    val context = LocalContext.current
    val resources = LocalResources.current
    val state = vm.state
    fun install() {
        val ready = vm.state as? UpdateState.Ready ?: return
        if (!ready.file.isFile) { vm.check(); return }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.audio", ready.file)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { onError(UiText(R.string.update_install_failed)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else onError(UiText(R.string.update_allow_install))
    }
    val title = when (state) {
        is UpdateState.Available -> resources.getString(R.string.update_download, state.update.version)
        is UpdateState.Ready -> resources.getString(R.string.update_install, state.version)
        else -> resources.getString(R.string.update_check)
    }
    val detail = when (state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> resources.getString(R.string.update_checking)
        UpdateState.Current -> resources.getString(R.string.update_current)
        is UpdateState.Failed -> state.reason.resolve(resources)
        is UpdateState.Available -> null
        is UpdateState.Downloading -> resources.getString(R.string.update_progress, state.percent)
        is UpdateState.Ready -> null
    }
    AboutAction(title, detail, enabled = state != UpdateState.Checking && state !is UpdateState.Downloading) {
        when (state) {
            is UpdateState.Available -> vm.download()
            is UpdateState.Ready -> if (context.packageManager.canRequestPackageInstalls()) install() else {
                runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", context.packageName, null))) }
                    .onFailure { onError(UiText(R.string.update_allow_install)) }
            }
            else -> vm.check()
        }
    }
}

@Composable
private fun AboutAction(title: String, detail: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
