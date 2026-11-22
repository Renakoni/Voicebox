package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.R
import androidx.compose.ui.platform.LocalResources

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun OutputPlayback(preview: PreviewState, durationMs: Long, enabled: Boolean,
    onToggle: () -> Unit, onSeek: (Int) -> Unit) {
    val resources = LocalResources.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledIconButton(onClick = onToggle, enabled = enabled && !preview.preparing,
            shape = StudioControlShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.size(48.dp).padding(4.dp)) {
            if (preview.preparing) CircularProgressIndicator(Modifier.size(24.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            else if (preview.playing) PauseIcon()
            else Icon(Icons.Default.PlayArrow, resources.getString(R.string.play_generated))
        }
        PlaybackTimeline(preview, durationMs, Modifier.weight(1f), onSeek)
    }
}

@Composable
private fun PlaybackTimeline(preview: PreviewState, durationMs: Long, modifier: Modifier, onSeek: (Int) -> Unit) {
    val resources = LocalResources.current
    val duration = (if (preview.durationMs > 0) preview.durationMs.toLong() else durationMs).coerceAtLeast(1)
    var draggedPosition by remember(preview.path) { mutableStateOf<Float?>(null) }
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is DragInteraction.Cancel) draggedPosition = null }
    }
    val position = draggedPosition ?: preview.positionMs.toFloat().coerceIn(0f, duration.toFloat())
    val seekEnabled = preview.path.isNotBlank() && !preview.preparing
    Column(modifier) {
        StudioSlider(value = position, onValueChange = { draggedPosition = it },
            onValueChangeFinished = {
                draggedPosition?.let { onSeek(it.toInt()) }
                draggedPosition = null
            },
            valueRange = 0f..duration.toFloat(), interactionSource = interactions,
            enabled = seekEnabled,
            modifier = Modifier.semantics { contentDescription = resources.getString(R.string.playback_position) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(position.toLong()), style = MaterialTheme.typography.labelMedium)
            Text(playbackTime(duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PauseIcon() {
    val resources = LocalResources.current
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp).semantics { contentDescription = resources.getString(R.string.pause_playback) }) {
        drawLine(color, Offset(size.width * .35f, size.height * .22f), Offset(size.width * .35f, size.height * .78f), 4.dp.toPx())
        drawLine(color, Offset(size.width * .65f, size.height * .22f), Offset(size.width * .65f, size.height * .78f), 4.dp.toPx())
    }
}

private fun playbackTime(ms: Long) = String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60)

@Composable
internal fun StreamPlaybackBar(state: StreamPlayback, onToggle: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val status = when {
        state.paused -> R.string.ming_stream_paused
        state.playing -> R.string.ming_stream_playing
        state.elapsedMs > 0 -> R.string.ming_stream_waiting
        else -> R.string.ming_stream_preparing
    }
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainerLow) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 60.dp).padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(resources.getString(status), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelLarge)
                    Text(playbackTime(state.elapsedMs), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                }
                IconButton(onClick = onToggle) {
                    if (state.paused) Icon(Icons.Default.PlayArrow, resources.getString(R.string.ming_stream_resume))
                    else PauseIcon()
                }
                IconButton(onClick = onStop) {
                    val color = LocalContentColor.current
                    Canvas(Modifier.size(24.dp).semantics { contentDescription = resources.getString(R.string.stop) }) {
                        drawRect(color, topLeft = Offset(size.width * .25f, size.height * .25f),
                            size = size.copy(width = size.width * .5f, height = size.height * .5f))
                    }
                }
            }
        }
    }
}
