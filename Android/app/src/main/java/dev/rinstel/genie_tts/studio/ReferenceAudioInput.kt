package dev.rinstel.genie_tts.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun ReferenceAudioInput(vm: StudioViewModel, path: String, name: String, durationMs: Long,
    pickAudio: () -> Unit, startRecording: () -> Unit, pickerTag: String, title: @Composable () -> Unit) {
    val recording = vm.recordingMs != null
    val enabled = !vm.busy && !vm.fileBusy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldToRecord(enabled, vm.recordingMs, startRecording, vm::finishReferenceRecording,
                vm::cancelReferenceRecording, Modifier.weight(1f))
            Surface(onClick = pickAudio, enabled = enabled, shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
                modifier = Modifier.weight(1f).height(80.dp).testTag(pickerTag)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("添加音频", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (path.isNotBlank()) {
            ReferencePlayback(vm.preview.takeIf { it.path == path } ?: PreviewState(), name, durationMs,
                enabled, onToggle = { vm.audio.toggle(path) })
        }
        if (vm.fileBusy && !recording) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ReferencePlayback(preview: PreviewState, name: String, durationMs: Long, enabled: Boolean,
    onToggle: () -> Unit) {
    val duration = (if (preview.durationMs > 0) preview.durationMs.toLong() else durationMs).coerceAtLeast(0)
    val position = preview.positionMs.toLong()
    val finished = !preview.playing && preview.positionMs >= duration
    val remaining = if (finished) duration else (duration - position).coerceAtLeast(if (preview.playing) 1 else 0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            // Round remaining seconds up so 0:00 is never shown while audio is still finishing.
            Text(if (duration > 0) recordingTime(((remaining + 999) / 1000) * 1000) else "—:—",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("reference_remaining"))
        }
        StudioButton(onClick = onToggle, enabled = enabled && !preview.preparing, style = StudioButtonStyle.Quiet,
            modifier = Modifier.testTag("play_reference")) {
            Text(if (preview.preparing) "加载中" else if (preview.playing) "暂停" else "试听")
        }
    }
}

@Composable
internal fun HoldToRecord(enabled: Boolean, elapsedMs: Long?, onStart: () -> Unit, onFinish: () -> Unit,
    onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val recording = elapsedMs != null
    val enabledNow by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    var cancelOnRelease by remember { mutableStateOf(false) }
    val cancelDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(recording) { if (recording) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val colors = MaterialTheme.colorScheme
    val label = when {
        recording && cancelOnRelease -> "松开取消"
        recording -> "松开完成"
        else -> "按住录音"
    }
    Surface(shape = RoundedCornerShape(12.dp),
        color = when { recording && cancelOnRelease -> colors.errorContainer
            recording -> colors.primaryContainer; else -> colors.surfaceContainerHigh },
        contentColor = when { recording && cancelOnRelease -> colors.onErrorContainer
            recording -> colors.onPrimaryContainer; !enabled -> colors.onSurface.copy(alpha = .38f)
            else -> colors.onSurface },
        modifier = modifier.height(80.dp).testTag("record_reference")
            // Keep the gesture alive when recording makes the rest of the form busy.
            .pointerInput(cancelDistance) {
                var startY = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        cancelOnRelease = false
                        startY = it.y
                        if (enabledNow) start()
                    },
                    onDrag = { change, _ -> cancelOnRelease = startY - change.position.y > cancelDistance },
                    onDragEnd = {
                        if (cancelOnRelease) cancel() else finish()
                        cancelOnRelease = false
                    },
                    onDragCancel = { cancel(); cancelOnRelease = false },
                )
            }.semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled && !recording) disabled()
                // TalkBack can start and finish without holding a pointer down.
                onClick(if (recording) "完成录音" else "开始录音") {
                    if (recording) { finish(); true }
                    else if (enabled) { start(); true } else false
                }
                if (recording) customActions = listOf(CustomAccessibilityAction("取消录音") { cancel(); true })
            }) {
        Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (recording) Text(recordingTime(elapsedMs!!), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.height(20.dp).testTag("recording_time"))
            else MicrophoneIcon()
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MicrophoneIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 24
        val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round)
        drawRoundRect(color, Offset(8 * unit, 2 * unit), Size(8 * unit, 13 * unit), CornerRadius(4 * unit), style = stroke)
        drawArc(color, 0f, 180f, false, Offset(5 * unit, 5 * unit), Size(14 * unit, 14 * unit), style = stroke)
        drawLine(color, Offset(12 * unit, 19 * unit), Offset(12 * unit, 22 * unit), stroke.width, StrokeCap.Round)
    }
}

private fun recordingTime(ms: Long) = String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60)
