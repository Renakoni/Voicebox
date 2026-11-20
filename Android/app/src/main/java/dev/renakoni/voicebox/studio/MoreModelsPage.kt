package dev.renakoni.voicebox.studio

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.moremodels.MingModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreModelsPage(vm: StudioViewModel, onBack: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val download = vm.officialDownload.takeIf { it.modelId == MingModel.ID }
    Column(modifier.fillMaxHeight().testTag("more_models_page")) {
        TopAppBar(title = { Text(resources.getString(R.string.more_models), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_settings))
            } }, windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(resources.getString(R.string.ming_name), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(resources.getString(R.string.ming_description), style = MaterialTheme.typography.bodyMedium)
            Text(resources.getString(if (vm.mingSupported) R.string.ming_download_size else R.string.ming_device_unsupported,
                Formatter.formatShortFileSize(LocalContext.current, vm.mingDownloadBytes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (download?.running == true) {
                download.label?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodySmall) }
                download.percent?.let { percent ->
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$percent%", style = MaterialTheme.typography.labelMedium)
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                StudioButton(onClick = vm::cancelOfficialDownload, style = StudioButtonStyle.Quiet) {
                    Text(resources.getString(R.string.ming_cancel_download))
                }
            } else {
                download?.error?.let { Text(it.resolve(resources), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ming_download_error")) }
                StudioButton(onClick = vm::downloadMing,
                    enabled = !vm.mingBackendInstalled && vm.mingSupported && vm.connected && !vm.busy && !vm.fileBusy,
                    modifier = Modifier.testTag("ming_download")) {
                    Text(resources.getString(if (vm.mingBackendInstalled) R.string.ming_installed else if (download?.error != null)
                        R.string.ming_retry else R.string.ming_download))
                }
            }
        }
    }
}
