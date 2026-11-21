package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.R
import androidx.compose.ui.platform.LocalResources

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceAvatar(name: String, path: String = "", modifier: Modifier = Modifier.size(48.dp)) {
    val resources = LocalResources.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        if (path.isNotBlank()) value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }
    // Each voice gets its own quiet earth tone and waveform, derived from its name, so a list
    // of placeholders reads as distinct people rather than one repeated stamp.
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val seed = name.trim().hashCode()
    val hue = 20.0 + Math.floorMod(seed, 97) * 1.1
    val (fill, ink) = remember(hue, dark) {
        if (dark) oklch(.31, .028, hue) to oklch(.84, .055, hue)
        else oklch(.91, .03, hue) to oklch(.46, .07, hue)
    }
    val bars = remember(seed) {
        val shapes = listOf(listOf(.3f, .6f, .9f, .6f, .3f), listOf(.45f, .85f, .55f, .75f, .35f),
            listOf(.35f, .7f, .5f, .9f, .4f), listOf(.5f, .4f, .9f, .45f, .6f))
        shapes[Math.floorMod(seed / 97, shapes.size)]
    }
    Box(modifier.clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, if (name.isBlank()) resources.getString(R.string.voice_avatar) else resources.getString(R.string.named_voice_avatar, name),
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val color = ink
            Canvas(Modifier.fillMaxSize(.5f)) {
                bars.forEachIndexed { index, height ->
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
    val resources = LocalResources.current
    var choosing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(96.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = false, radius = 52.dp),
                enabled = enabled, role = Role.Button, onClickLabel = resources.getString(R.string.change_avatar)) { choosing = true }) {
            VoiceAvatar(name, path, Modifier.fillMaxSize())
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface,
                border = androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.background),
                modifier = Modifier.size(32.dp).align(Alignment.BottomEnd)) {
                Icon(Icons.Default.Edit, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.surface)
            }
        }
    }
    if (choosing) ModalBottomSheet(onDismissRequest = { choosing = false }, dragHandle = null,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        listOf(resources.getString(R.string.choose_photo) to pickPhoto, resources.getString(R.string.take_photo) to takePhoto).forEachIndexed { index, (label, action) ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().clickable { choosing = false; action() }
                .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxWidth().clickable { choosing = false }.heightIn(min = 56.dp).padding(16.dp),
            contentAlignment = Alignment.Center) {
            Text(resources.getString(R.string.cancel), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
