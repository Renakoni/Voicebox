package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.R
import androidx.compose.ui.platform.LocalResources

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun StarInvitation(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val phase = remember { Animatable(0f) }
    val owner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(160), label = "star_press")
    LaunchedEffect(owner, visible) {
        if (!visible) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (phase.value < 1f) phase.animateTo(1f,
                tween((2500 * (1f - phase.value)).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    val highlight = if (dark) colors.onSurface else colors.surfaceContainerLow
    Box(modifier.fillMaxWidth().heightIn(min = 132.dp)
        .onGloballyPositioned { visible = it.boundsInWindow().height >= it.size.height * .65f }
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = resources.getString(R.string.github_star_action), onClick = onClick)) {
        Box(Modifier.matchParentSize().drawWithCache {
            val center = Offset(size.width - 55.dp.toPx(), size.height * .5f)
            val radius = 32.dp.toPx()
            val glowRadius = radius * 2.25f
            val glow = Brush.radialGradient(listOf(colors.primary.copy(alpha = if (dark) .16f else .10f),
                Color.Transparent), center, glowRadius)
            val metal = Brush.linearGradient(listOf(lerp(colors.primary, highlight, .75f), colors.primary,
                colors.primaryContainer), center - Offset(radius, radius), center + Offset(radius, radius))
            val points = List(10) { index ->
                val angle = -PI / 2 + index * PI / 5
                val distance = if (index % 2 == 0) 1f else .46f
                Offset(cos(angle).toFloat() * distance, sin(angle).toFloat() * distance)
            }
            val yaw = .2f
            val tilt = -.16f
            fun project(point: Offset, depth: Float): Offset {
                val x = point.x * radius
                val y = point.y * radius
                val z = depth * radius
                val turnedX = x * cos(yaw) + z * sin(yaw)
                val turnedZ = -x * sin(yaw) + z * cos(yaw)
                val perspective = radius * 5 / (radius * 5 + turnedZ)
                return center + Offset((turnedX * cos(tilt) - y * sin(tilt)) * perspective,
                    (turnedX * sin(tilt) + y * cos(tilt)) * perspective)
            }
            fun polygon(vertices: List<Offset>) = Path().apply {
                vertices.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            val front = points.map { project(it, .10f) }
            val back = points.map { project(it, -.14f) + Offset(0f, 3.dp.toPx()) }
            val hub = project(Offset.Zero, .18f)
            val star = polygon(front)
            val sides = front.indices.map { i ->
                val next = (i + 1) % front.size
                polygon(listOf(back[i], back[next], front[next], front[i]))
            }
            val facets = front.indices.map { i ->
                val light = cos(i * PI / 5 - 2).toFloat()
                val color = if (light > 0f) highlight else colors.primaryContainer
                polygon(listOf(hub, front[i], front[(i + 1) % front.size])) to color.copy(alpha = kotlin.math.abs(light) * .38f)
            }
            val outline = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val oval = Size(radius * 3.65f, radius * 1.55f)
            val corner = center - Offset(oval.width / 2, oval.height / 2)
            val track = Stroke(.65.dp.toPx())
            val arc = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                drawCircle(glow, glowRadius, center)
                rotate(-26f, center) {
                    drawOval(colors.outlineVariant.copy(alpha = .55f), corner, oval, style = track)
                    drawArc(colors.primary.copy(alpha = .55f), 210f + phase.value * 360f, 50f, false,
                        corner, oval, style = arc)
                }
                scale(1f - press * .06f, center) {
                    sides.forEach { drawPath(it, colors.primaryContainer) }
                    drawPath(star, metal)
                    facets.forEach { (face, color) -> drawPath(face, color) }
                    drawPath(star, highlight.copy(alpha = .35f), style = outline)
                }
            }
        })
        Column(Modifier.align(Alignment.CenterStart).padding(end = 115.dp).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(resources.getString(R.string.github_star_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(resources.getString(R.string.github_star), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), color = colors.onSurface,
                    modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = colors.primary)
            }
        }
    }
}
