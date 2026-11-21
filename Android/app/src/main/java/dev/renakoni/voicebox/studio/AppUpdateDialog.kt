package dev.renakoni.voicebox.studio

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.i18n.UiText
import io.noties.markwon.Markwon

@Composable
internal fun AppUpdateEntry(onError: (UiText) -> Unit, openLink: (String) -> Unit) {
    val vm: AppUpdateViewModel = viewModel()
    val context = LocalContext.current
    var show by rememberSaveable { mutableStateOf(false) }
    var installAfterDownload by rememberSaveable { mutableStateOf(false) }
    val state = vm.state
    val release = vm.release
    AboutAction(stringResource(R.string.update_check), stringResource(R.string.update_release_notes)) {
        show = true
        if (state !is UpdateState.Ready && state !is UpdateState.Downloading) vm.check()
    }
    if (!show) return

    fun install() {
        val ready = vm.state as? UpdateState.Ready ?: return
        if (!ready.file.isFile) { vm.check(); return }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.audio", ready.file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { onError(UiText(R.string.update_install_failed)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else onError(UiText(R.string.update_allow_install))
    }
    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) install() else {
            runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", context.packageName, null))) }
                .onFailure { onError(UiText(R.string.update_allow_install)) }
        }
    }
    LaunchedEffect(state is UpdateState.Ready) {
        if (state is UpdateState.Ready && installAfterDownload) {
            installAfterDownload = false
            requestInstall()
        }
    }
    AppUpdateDialog(release, state, onDismiss = { show = false }, openLink = openLink) {
        when {
            state is UpdateState.Ready -> requestInstall()
            release == null -> vm.check()
            else -> { installAfterDownload = true; vm.download() }
        }
    }
}

// Adapted from NextVol's UpdatesAvailableDialog. See assets/ui-licenses/NOTICE.txt.
@Composable
private fun AppUpdateDialog(release: AppUpdate?, state: UpdateState, onDismiss: () -> Unit,
                            openLink: (String) -> Unit, onConfirm: () -> Unit) {
    val downloading = state is UpdateState.Downloading
    val newer = release != null && release.code > BuildConfig.VERSION_CODE
    val title = when {
        newer -> R.string.update_available
        state == UpdateState.Current && release != null -> R.string.update_current
        else -> R.string.update_check
    }
    val releasesUrl = "https://github.com/Renakoni/Voicebox/releases"
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        icon = { Icon(painterResource(R.drawable.update_archive), null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (state == UpdateState.Checking) {
                    Text(stringResource(R.string.update_checking))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                if (state is UpdateState.Failed) Text(state.reason.resolve(LocalResources.current), color = MaterialTheme.colorScheme.error)
                if (state == UpdateState.Current && release == null) Text(stringResource(R.string.update_no_release))
                if (release != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                        val colors = MaterialTheme.colorScheme
                        val codes = SpanStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.Normal)
                        val versions = buildAnnotatedString {
                            if (newer) {
                                append(BuildConfig.VERSION_NAME)
                                withStyle(codes) { append(" (${BuildConfig.VERSION_CODE})") }
                                append("  →  ")
                            }
                            withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) { append(release.version) }
                            withStyle(codes) { append(" (${release.code})") }
                        }
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            Text(versions, modifier = Modifier.horizontalScroll(rememberScrollState()), maxLines = 1,
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                    if (state is UpdateState.Downloading) {
                        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    } else HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.update_release_notes), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { openLink(release.releaseUrl) }) { Text("GitHub Release") }
                    }
                    ReleaseNotes(release.notes.ifBlank { stringResource(R.string.update_no_notes) })
                }
            }
        },
        confirmButton = {
            if (newer || state is UpdateState.Failed) {
                Button(onClick = onConfirm, enabled = !downloading, modifier = Modifier.fillMaxWidth()) {
                    if (state is UpdateState.Downloading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_progress, state.percent))
                    } else {
                        if (newer) {
                            Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(if (newer) R.string.update_install_action else R.string.update_check))
                    }
                }
            }
        },
        dismissButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !downloading, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (newer) R.string.update_later else R.string.update_close))
                }
                if (newer || release == null && state != UpdateState.Checking) {
                    OutlinedButton(onClick = { openLink(release?.apk?.url ?: releasesUrl) },
                        enabled = !downloading, modifier = Modifier.weight(1f)) {
                        Text(if (newer) stringResource(R.string.update_manual) else "GitHub Releases")
                    }
                }
            }
        },
    )
}

@Composable
private fun ReleaseNotes(markdown: String) {
    val context = LocalContext.current
    val renderer = remember(context) { Markwon.create(context) }
    val content = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val links = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(factory = { TextView(it) }, modifier = Modifier.fillMaxWidth(), update = { view ->
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        view.setTextColor(foreground)
        view.setLinkTextColor(links)
        renderer.setParsedMarkdown(view, content)
    })
}
