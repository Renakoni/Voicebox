@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.rinstel.genie_tts.studio

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
import dev.rinstel.genie_tts.BuildConfig
import dev.rinstel.genie_tts.R

@Composable
internal fun AboutPage(onBack: () -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure { onError("无法打开链接，请检查浏览器是否可用。") }
    }
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text("关于", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.matchParentSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), "Voicebox 图标", Modifier.matchParentSize())
                }
                Text("Voicebox", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text("本地语音合成与音色克隆", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AboutLink("GitHub", "github.com/Renakoni/Voicebox", "https://github.com/Renakoni/Voicebox", openLink)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AboutLink("问题反馈", "GitHub Issues", "https://github.com/Renakoni/Voicebox/issues", openLink)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AboutLink("开源许可", "MIT License", "https://github.com/Renakoni/Voicebox/blob/main/LICENSE", openLink)
        }
    }
}

@Composable
private fun AboutLink(title: String, detail: String, url: String, openLink: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { openLink(url) }.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
