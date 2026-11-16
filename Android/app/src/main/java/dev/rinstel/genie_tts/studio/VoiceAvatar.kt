package dev.rinstel.genie_tts.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceAvatar(name: String, path: String = "", modifier: Modifier = Modifier.size(48.dp)) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        if (path.isNotBlank()) value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, if (name.isBlank()) "音色头像" else "$name 的头像",
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .75f)
            Canvas(Modifier.fillMaxSize(.5f)) {
                listOf(.3f, .6f, .9f, .6f, .3f).forEachIndexed { index, height ->
                    val x = size.width * (index + 1) / 6
                    drawLine(color, Offset(x, size.height * (1 - height) / 2),
                        Offset(x, size.height * (1 + height) / 2),
                        strokeWidth = size.width / 12, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun VoiceAvatarEditor(name: String, path: String, enabled: Boolean, pickPhoto: () -> Unit, takePhoto: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(96.dp).testTag("edit_avatar")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = false, radius = 52.dp),
                enabled = enabled, role = Role.Button, onClickLabel = "更换头像") { choosing = true }) {
            VoiceAvatar(name, path, Modifier.fillMaxSize())
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp).align(Alignment.BottomEnd)) {
                Icon(Icons.Default.Edit, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
    if (choosing) ModalBottomSheet(onDismissRequest = { choosing = false }, dragHandle = null,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        listOf("从相册选择" to pickPhoto, "拍照" to takePhoto).forEachIndexed { index, (label, action) ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().clickable { choosing = false; action() }
                .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxWidth().clickable { choosing = false }.heightIn(min = 56.dp).padding(16.dp),
            contentAlignment = Alignment.Center) {
            Text("取消", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
