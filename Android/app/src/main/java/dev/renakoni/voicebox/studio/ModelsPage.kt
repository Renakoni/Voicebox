package dev.renakoni.voicebox.studio

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelsPage(vm: StudioViewModel, onBack: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val context = LocalContext.current
    Column(modifier.fillMaxHeight().testTag("models_page")) {
        TopAppBar(title = { Text(resources.getString(R.string.models), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_settings))
            } }, windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            vm.backendPackages.forEachIndexed { index, archive ->
                val spec = archive.spec
                val download = vm.officialDownload.takeIf { it.packageId == spec.id }
                val installed = vm.installedPackages
                if (index > 0) HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.displayName, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        if (installed?.contains(spec.id) == true) Text(resources.getString(R.string.backend_installed),
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("${spec.id}_installed"))
                    }
                    when {
                        installed == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        spec.id in installed -> Unit
                        download?.running == true -> {
                            val percent = download.percent
                            if (percent != null) {
                                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                                Text("$percent%", style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.testTag("${spec.id}_progress"))
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            StudioButton(onClick = vm::cancelOfficialDownload, style = StudioButtonStyle.Quiet,
                                modifier = Modifier.testTag("${spec.id}_cancel")) { Text(resources.getString(R.string.cancel_download)) }
                        }
                        else -> {
                            if (!archive.supported) Text(resources.getString(R.string.backend_device_unsupported))
                            else if (!spec.bundled) Text(resources.getString(R.string.backend_download_size,
                                Formatter.formatShortFileSize(context, spec.downloadBytes)), style = MaterialTheme.typography.bodyMedium)
                            download?.error?.let { Text(it.resolve(resources), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("${spec.id}_error")) }
                            StudioButton(onClick = { vm.installBackend(spec.id) },
                                enabled = archive.supported && vm.connected && !vm.busy && !vm.fileBusy,
                                modifier = Modifier.testTag("${spec.id}_download")) {
                                Text(resources.getString(when {
                                    spec.bundled -> R.string.install_bundled
                                    download?.error != null -> R.string.backend_retry
                                    else -> R.string.backend_download
                                }))
                            }
                        }
                    }
                }
            }
        }
    }
}
