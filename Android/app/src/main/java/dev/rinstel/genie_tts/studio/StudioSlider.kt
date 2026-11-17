package dev.rinstel.genie_tts.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StudioSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, steps: Int = 0,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }) {
    val scheme = MaterialTheme.colorScheme
    val colors = SliderDefaults.colors(thumbColor = scheme.onSurfaceVariant,
        activeTrackColor = scheme.onSurfaceVariant, inactiveTrackColor = scheme.outlineVariant)
    Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished, enabled = enabled, interactionSource = interactionSource,
        colors = colors, modifier = modifier.heightIn(min = 48.dp),
        thumb = {
            Box(Modifier.size(width = 12.dp, height = 24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).indication(interactionSource, ripple(bounded = false, radius = 20.dp))
                    .background(if (enabled) scheme.onSurfaceVariant else scheme.onSurface.copy(alpha = .38f), CircleShape))
            }
        },
        track = { state ->
            SliderDefaults.Track(state, Modifier.height(2.dp), enabled = enabled, colors = colors,
                thumbTrackGapSize = 0.dp, drawStopIndicator = null, drawTick = { _, _ -> })
        })
}
