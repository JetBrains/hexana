package org.jetbrains.hexana.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.CurrentTheme.TrialWidget
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.Color as AwtColor
import javax.swing.UIManager
import kotlin.math.max

internal const val ShareFeedbackButtonLabel = "Share Feedback"
private const val borderWidthPx = 1.5f

private data class ShareFeedbackButtonColors(
    val foreground: Color,
    val background: Color,
    val borderColor: Color,
    val hoverForeground: Color,
    val hoverBackground: Color,
    val hoverBorderColor: Color,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShareFeedbackButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    var hovered by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberShareFeedbackButtonColors()
    val foreground = if (hovered) colors.hoverForeground else colors.foreground
    val background = if (hovered) colors.hoverBackground else colors.background
    val borderColor = if (hovered) colors.hoverBorderColor else colors.borderColor

    Box(
        modifier = modifier
            .onPointerEvent(PointerEventType.Enter) {
                hovered = true
            }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = false
            }
            .drawBehind {
                val cornerRadius = size.height / 2f
                drawRoundRect(
                    color = background,
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                )

                if (borderColor.alpha != 0f && borderColor != background) {
                    val inset = borderWidthPx / 2f
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, inset),
                        size = Size(
                            width = max(size.width - borderWidthPx, 0f),
                            height = max(size.height - borderWidthPx, 0f),
                        ),
                        cornerRadius = CornerRadius(
                            x = max(cornerRadius - inset, 0f),
                            y = max(cornerRadius - inset, 0f),
                        ),
                        style = Stroke(width = borderWidthPx),
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 3.dp),
    ) {
        Text(
            text = ShareFeedbackButtonLabel,
            style = TextStyle(
                color = foreground,
                fontSize = 13.sp,
            ),
        )
    }
}

@Composable
private fun rememberShareFeedbackButtonColors(): ShareFeedbackButtonColors {
    val isDark = JewelTheme.isDark

    return remember(isDark) {
        val panelForeground = UIManager.getColor("Panel.foreground") ?: defaultForegroundColor()
        val panelBackground = UIManager.getColor("Panel.background") ?: defaultBackgroundColor()

        ShareFeedbackButtonColors(
            foreground = colorOrFallback(TrialWidget.Default.FOREGROUND) { panelForeground }.toComposeColor(),
            background = colorOrFallback(TrialWidget.Default.BACKGROUND) { panelBackground }.toComposeColor(),
            borderColor = colorOrFallback(TrialWidget.Default.BORDER_COLOR) { panelForeground }.toComposeColor(),
            hoverForeground = colorOrFallback(TrialWidget.Default.HOVER_FOREGROUND) { panelForeground }.toComposeColor(),
            hoverBackground = colorOrFallback(TrialWidget.Default.HOVER_BACKGROUND) { JBUI.CurrentTheme.ActionButton.hoverBackground() }.toComposeColor(),
            hoverBorderColor = colorOrFallback(TrialWidget.Default.HOVER_BORDER_COLOR) { panelForeground }.toComposeColor(),
        )
    }
}

private fun colorOrFallback(color: AwtColor, fallbackColor: () -> AwtColor): AwtColor {
    val colorName = (color as? JBColor)?.name ?: return color
    return if (UIManager.getColor(colorName) != null) color else fallbackColor()
}

private fun defaultForegroundColor(): AwtColor = if (JBColor.isBright()) AwtColor.BLACK else AwtColor.WHITE

private fun defaultBackgroundColor(): AwtColor = if (JBColor.isBright()) AwtColor.WHITE else AwtColor(0x2B, 0x2D, 0x30)

private fun AwtColor.toComposeColor(): Color = Color(red = red, green = green, blue = blue, alpha = alpha)
