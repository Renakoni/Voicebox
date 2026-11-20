// Adapted from Microsoft Fluent UI Android's tokenized Button (MIT).
// Source and license: assets/ui-licenses/NOTICE.txt and fluentui-LICENSE.txt.
package dev.renakoni.voicebox.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal enum class StudioButtonStyle { Primary, Secondary, Quiet }
internal val StudioControlShape = RoundedCornerShape(6.dp)

/** Fluent's compact button layout, with the studio's neutral palette and Android touch sizing. */
@Composable
internal fun StudioButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    style: StudioButtonStyle = StudioButtonStyle.Primary, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(when {
        !enabled && style == StudioButtonStyle.Primary -> colors.surfaceContainerHigh
        style == StudioButtonStyle.Primary -> colors.onSurface.copy(alpha = if (pressed) .8f else if (hovered) .9f else 1f)
        enabled && (pressed || hovered) -> colors.surfaceContainerHigh
        style == StudioButtonStyle.Secondary -> colors.surfaceContainer
        else -> Color.Transparent
    }, tween(140), label = "button background")
    val foreground = when {
        !enabled -> colors.onSurface.copy(alpha = .38f)
        style == StudioButtonStyle.Primary -> colors.surface
        style == StudioButtonStyle.Quiet -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    val border = when {
        focused && enabled -> BorderStroke(2.dp, colors.primary)
        style == StudioButtonStyle.Secondary -> BorderStroke(1.dp, colors.outlineVariant)
        else -> null
    }
    Box(modifier.minimumInteractiveComponentSize().heightIn(min = 36.dp)
        .background(background, StudioControlShape).clip(StudioControlShape)
        .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled,
            role = Role.Button, onClick = onClick)
        .then(if (border != null) Modifier.border(border, StudioControlShape) else Modifier),
        propagateMinConstraints = true) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            val weight = when (style) {
                StudioButtonStyle.Primary -> FontWeight.SemiBold
                StudioButtonStyle.Secondary -> FontWeight.Medium
                StudioButtonStyle.Quiet -> FontWeight.Normal
            }
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = weight, textAlign = TextAlign.Center)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
    }
}
