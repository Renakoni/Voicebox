package dev.renakoni.voicebox.studio

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.i18n.UiText

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
        if (state == UpdateState.Idle || state == UpdateState.Current || state is UpdateState.Failed) vm.check()
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
@OptIn(ExperimentalMaterial3Api::class)
private fun AppUpdateDialog(release: AppUpdate?, state: UpdateState, onDismiss: () -> Unit,
                            openLink: (String) -> Unit, onConfirm: () -> Unit) {
    val downloading = state is UpdateState.Downloading
    val newer = release != null
    val title = when {
        newer -> R.string.update_available
        state == UpdateState.Current -> R.string.update_current
        else -> R.string.update_check
    }
    val colors = MaterialTheme.colorScheme
    BasicAlertDialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlyphTile(size = 36.dp) {
                        Icon(painterResource(R.drawable.update_archive), null, Modifier.size(20.dp))
                    }
                    Text(stringResource(title), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state) {
                        UpdateState.Checking -> {
                            Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        is UpdateState.Failed -> Text(state.reason.resolve(LocalResources.current),
                            style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        UpdateState.Current -> {
                            Text(stringResource(R.string.update_no_release), style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> Unit
                    }
                    if (release != null) {
                        val codes = SpanStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.Normal)
                        val versions = buildAnnotatedString {
                            append(BuildConfig.VERSION_NAME)
                            withStyle(codes) { append(" (${BuildConfig.VERSION_CODE})") }
                            append("  →  ")
                            withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) { append(release.version) }
                            withStyle(codes) { append(" (${release.code})") }
                        }
                        Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                            Text(versions, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), maxLines = 1,
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                        }
                        if (state is UpdateState.Downloading) {
                            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        HorizontalDivider()
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.update_release_notes), Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleSmall)
                            StudioButton({ openLink(release.releaseUrl) }, style = StudioButtonStyle.Quiet) {
                                Text(stringResource(R.string.update_github))
                            }
                        }
                        when {
                            release.notesHtml == null -> Text(stringResource(R.string.update_notes_on_github),
                                style = MaterialTheme.typography.bodyMedium)
                            release.notesHtml.isBlank() -> Text(stringResource(R.string.update_no_notes),
                                style = MaterialTheme.typography.bodyMedium)
                            else -> ReleaseNotes(release.notesHtml)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (newer || state is UpdateState.Failed) {
                        StudioButton(onConfirm, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !downloading) {
                            val label = when {
                                state is UpdateState.Downloading -> stringResource(R.string.update_progress, state.percent)
                                state is UpdateState.Failed -> stringResource(R.string.update_retry)
                                else -> stringResource(R.string.update_install_action)
                            }
                            if (newer && state !is UpdateState.Failed && !downloading) {
                                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                            }
                            Text(label)
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        StudioButton(onDismiss, enabled = !downloading, style = StudioButtonStyle.Quiet) {
                            Text(stringResource(if (newer) R.string.update_later else R.string.update_close))
                        }
                        if (state != UpdateState.Checking) {
                            val url = release?.apk?.url ?: APP_RELEASES_URL
                            StudioButton({ openLink(url) }, enabled = !downloading, style = StudioButtonStyle.Secondary) {
                                Text(stringResource(if (newer) R.string.update_manual else R.string.update_github))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(html: String) {
    val content = remember(html) { HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).trimEnd() }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val links = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(factory = { TextView(it) }, modifier = Modifier.fillMaxWidth(), update = { view ->
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        view.setTextColor(foreground)
        view.setLinkTextColor(links)
        view.movementMethod = LinkMovementMethod.getInstance()
        view.text = content
    })
}
