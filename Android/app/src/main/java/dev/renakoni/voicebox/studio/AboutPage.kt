@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.core.i18n.UiText
import androidx.compose.ui.platform.LocalResources

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.BuildConfig
import dev.renakoni.voicebox.R

internal const val PROJECT_URL = dev.renakoni.voicebox.core.GitHubReleases.projectUrl

@Composable
internal fun AboutPage(onBack: () -> Unit, onError: (UiText) -> Unit, openLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text(resources.getString(R.string.about), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, resources.getString(R.string.back_settings)) } },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(15.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.matchParentSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(resources.getString(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(resources.getString(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(resources.getString(R.string.app_description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    AppUpdateEntry(onError, openLink)
                    AboutAction("GitHub", "Renakoni/Voicebox") { openLink(PROJECT_URL) }
                    AboutAction(resources.getString(R.string.report_issue)) { openLink("$PROJECT_URL/issues") }
                    AboutAction(resources.getString(R.string.open_source_license), "AGPL-3.0") { openLink("$PROJECT_URL/blob/main/LICENSE") }
                }
            }
        }
    }
}

@Composable
internal fun AboutAction(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
