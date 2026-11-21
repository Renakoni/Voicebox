package dev.renakoni.voicebox.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Outer radius for grouped surfaces; inner seams stay tight so a group reads as one object. */
internal val StudioCardRadius = 18.dp
private val StudioSeamRadius = 5.dp
internal val StudioCardShape = RoundedCornerShape(StudioCardRadius)

/** Shape for one row inside a vertically stacked group, e.g. a list split into lazy items. */
internal fun groupedShape(index: Int, count: Int): Shape {
    val top = if (index == 0) StudioCardRadius else StudioSeamRadius
    val bottom = if (index == count - 1) StudioCardRadius else StudioSeamRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A quiet raised sheet: one tonal step above the page with a hairline edge, no shadow. */
@Composable
internal fun StudioCard(modifier: Modifier = Modifier, shape: Shape = StudioCardShape,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clip(shape).background(colors.surfaceContainer)
        .border(1.dp, colors.outlineVariant.copy(alpha = .45f), shape), content = content)
}

/** Small-caps style group label that sits above a card. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = .6.sp, fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A low-key pill for status words such as “unsupported”. */
@Composable
internal fun StatusTag(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Rounded tile that frames a glyph; used as the leading mark of a model family. */
@Composable
internal fun GlyphTile(modifier: Modifier = Modifier, size: Dp = 40.dp, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(colors.secondaryContainer),
        contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer) { content() }
    }
}

/**
 * Thin segmented meter: one segment per installable part.
 * Reads as “how far along” at a glance without a percentage.
 */
@Composable
internal fun SegmentMeter(total: Int, done: Int, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { index ->
            val color by animateColorAsState(if (index < done) colors.primary else colors.outlineVariant,
                tween(260), label = "segment")
            Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}
