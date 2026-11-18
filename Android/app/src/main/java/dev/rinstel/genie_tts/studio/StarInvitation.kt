package dev.rinstel.genie_tts.studio

import dev.rinstel.genie_tts.R
import androidx.compose.ui.platform.LocalResources

import android.animation.ValueAnimator
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
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

/** A finite, draw-only sequence; no frames are requested after the star settles. */
@Composable
internal fun StarInvitation(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val phase = remember { Animatable(if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f) }
    val owner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(160), label = "star_press")
    LaunchedEffect(owner, visible) {
        if (!visible) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!ValueAnimator.areAnimatorsEnabled()) phase.snapTo(1f)
            else if (phase.value < 1f) phase.animateTo(1f,
                tween((5200 * (1f - phase.value)).toInt().coerceAtLeast(1), easing = LinearEasing))
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
            val heights = listOf(.3f, .65f, 1f, .5f, .8f)
            val frontPath = Path()
            val facePath = Path()
            val linePath = Path()
            val outline = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                val time = phase.value
                val morph = smoothStar((time - .10f) / .34f)
                val fill = smoothStar((morph - .65f) / .35f)
                val scale = 1f - press * .06f
                val origin = center + Offset(0f, -sin(time * PI).toFloat() * 3.dp.toPx())
                val yaw = -.6f + .8f * smoothStar((time - .2f) / .65f)
                val tilt = -.16f
                fun project(point: Offset, depth: Float): Offset {
                    val x = point.x * radius * scale
                    val y = point.y * radius * scale
                    val z = depth * radius
                    val turnedX = x * cos(yaw) + z * sin(yaw)
                    val turnedZ = -x * sin(yaw) + z * cos(yaw)
                    val perspective = radius * 5 / (radius * 5 + turnedZ)
                    return origin + Offset((turnedX * cos(tilt) - y * sin(tilt)) * perspective,
                        (turnedX * sin(tilt) + y * cos(tilt)) * perspective)
                }
                val front = points.map { project(it, .10f) }
                val back = points.map { project(it, -.14f) + Offset(0f, 3.dp.toPx()) }
                val hub = project(Offset.Zero, .18f)
                drawCircle(glow, glowRadius, center, alpha = (.55f + fill * .45f + press * .15f).coerceAtMost(1f))
                val orbitAlpha = smoothStar((time - .18f) / .25f) * .55f
                rotate(-26f, origin) {
                    val oval = Size(radius * 3.65f, radius * 1.55f)
                    val corner = origin - Offset(oval.width / 2, oval.height / 2)
                    drawOval(colors.outlineVariant.copy(alpha = orbitAlpha), corner, oval, style = Stroke(.65.dp.toPx()))
                    drawArc(colors.primary.copy(alpha = orbitAlpha), time * 310 - 100, 50f, false,
                        corner, oval, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                }
                if (fill > 0f) {
                    for (index in front.indices) {
                        val next = (index + 1) % front.size
                        facePath.reset()
                        facePath.moveTo(back[index].x, back[index].y)
                        facePath.lineTo(back[next].x, back[next].y)
                        facePath.lineTo(front[next].x, front[next].y)
                        facePath.lineTo(front[index].x, front[index].y)
                        facePath.close()
                        drawPath(facePath, colors.primaryContainer, alpha = fill)
                    }
                    frontPath.reset()
                    front.forEachIndexed { index, point ->
                        if (index == 0) frontPath.moveTo(point.x, point.y) else frontPath.lineTo(point.x, point.y)
                    }
                    frontPath.close()
                    drawPath(frontPath, metal, alpha = fill)
                    for (index in front.indices) {
                        val next = front[(index + 1) % front.size]
                        facePath.reset()
                        facePath.moveTo(hub.x, hub.y)
                        facePath.lineTo(front[index].x, front[index].y)
                        facePath.lineTo(next.x, next.y)
                        facePath.close()
                        val light = cos(index * PI / 5 - time * 2).toFloat()
                        drawPath(facePath, if (light > 0) highlight else colors.primaryContainer,
                            alpha = fill * kotlin.math.abs(light) * .38f)
                    }
                    val sweep = ((time - .48f) / .38f).coerceIn(0f, 1f)
                    if (sweep > 0 && sweep < 1) {
                        val beamX = origin.x + (sweep * 3 - 1.5f) * radius
                        val sheen = Brush.linearGradient(listOf(Color.Transparent, highlight.copy(alpha = .65f), Color.Transparent),
                            Offset(beamX - radius * .3f, origin.y - radius), Offset(beamX + radius * .3f, origin.y + radius))
                        clipPath(frontPath) { drawRect(sheen, alpha = fill) }
                    }
                    drawPath(frontPath, highlight.copy(alpha = .35f * fill), style = outline)
                }
                for (index in 0..4) {
                    val x = center.x + (index - 2) * 10.dp.toPx()
                    val halfHeight = radius * heights[index] * .75f
                    val wave = listOf(Offset(x, center.y - halfHeight), Offset(x, center.y), Offset(x, center.y + halfHeight))
                    linePath.reset()
                    for (vertex in 0..2) {
                        val target = front[(index * 2 + vertex) % front.size]
                        val point = wave[vertex] + (target - wave[vertex]) * morph
                        if (vertex == 0) linePath.moveTo(point.x, point.y) else linePath.lineTo(point.x, point.y)
                    }
                    drawPath(linePath, colors.primary,
                        alpha = 1f - fill * .7f, style = Stroke((4f - 3f * morph).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val sparks = sin(PI * ((time - .08f) / .75f).coerceIn(0f, 1f)).toFloat()
                if (sparks > 0f) repeat(9) { index ->
                    val angle = index * 2 * PI / 9 + time * .8
                    val distance = radius * (1.9f - morph * .45f + (index % 3) * .12f)
                    val point = origin + Offset(cos(angle).toFloat() * distance, sin(angle).toFloat() * distance * .66f)
                    drawCircle(highlight, (if (index % 3 == 0) 1.3f else .75f).dp.toPx(), point, alpha = sparks * .65f)
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

private fun smoothStar(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
