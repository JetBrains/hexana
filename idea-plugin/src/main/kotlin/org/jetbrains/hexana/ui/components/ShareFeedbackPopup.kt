package org.jetbrains.hexana.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private data class FeedbackPopupPalette(
    val background: Brush,
    val borderColor: Color,
    val accentColor: Color,
    val titleColor: Color,
    val bodyColor: Color,
    val hintColor: Color,
    val buttonGlowColor: Color,
)

private data class FeedbackActionPalette(
    val gradient: List<Color>,
    val hoverGradient: List<Color>,
    val borderColor: Color,
    val hoverBorderColor: Color,
    val titleColor: Color,
    val bodyColor: Color,
)

@Composable
fun ShareFeedbackPopup(
    modifier: Modifier = Modifier,
    showInIdeFeedbackAction: Boolean = true,
    onReportBug: () -> Unit,
    onRateOnMarketplace: () -> Unit,
    onShareFeedback: () -> Unit,
    onNeverShowAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val popupPalette = rememberFeedbackPopupPalette()
    val bugPalette = rememberBugButtonPalette()
    val marketplacePalette = rememberMarketplaceButtonPalette()
    val feedbackPalette = rememberFeedbackButtonPalette()
    val shape = RoundedCornerShape(26.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .widthIn(max = 336.dp)
            .shadow(28.dp, shape = shape, clip = false)
            .clip(shape)
            .background(brush = popupPalette.background, shape = shape)
            .border(width = 1.dp, color = popupPalette.borderColor, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Tell us what you found",
                    style = TextStyle(
                        color = popupPalette.titleColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = if (showInIdeFeedbackAction) {
                        "Choose the fastest route. Report a bug on GitHub, rate Hexana on Marketplace, or open a short Hexana note in an editor tab."
                    } else {
                        "Report a bug on GitHub with technical context, or rate Hexana on Marketplace."
                    },
                    style = TextStyle(
                        color = popupPalette.bodyColor,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                )
            }

            Text(
                text = "Not now",
                style = TextStyle(
                    color = popupPalette.accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            )
        }

        GradientFeedbackActionButton(
            title = "Report a bug",
            subtitle = "Open a GitHub issue with reproduction details and technical context.",
            palette = bugPalette,
            glowColor = popupPalette.buttonGlowColor,
            onClick = onReportBug,
        )

        GradientFeedbackActionButton(
            title = "Rate us on Marketplace",
            subtitle = "Open the Hexana plugin page and leave a Marketplace rating.",
            palette = marketplacePalette,
            glowColor = popupPalette.buttonGlowColor,
            onClick = onRateOnMarketplace,
        )

        if (showInIdeFeedbackAction) {
            GradientFeedbackActionButton(
                title = "Share feedback",
                subtitle = "Open a calm in-editor note for workflows, rough edges, and optional follow-up.",
                palette = feedbackPalette,
                glowColor = popupPalette.buttonGlowColor,
                onClick = onShareFeedback,
            )
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color = popupPalette.borderColor.copy(alpha = 0.7f))
        )

        Text(
            text = "Sensitive file contents are not attached automatically. Review the issue before submitting it.",
            style = TextStyle(
                color = popupPalette.hintColor,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
        )

        Text(
            text = "Never show again",
            style = TextStyle(
                color = popupPalette.hintColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNeverShowAgain,
                )
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GradientFeedbackActionButton(
    title: String,
    subtitle: String,
    palette: FeedbackActionPalette,
    glowColor: Color,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.015f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "${title}Scale",
    )
    val elevation by animateFloatAsState(
        targetValue = if (hovered) 20f else 12f,
        animationSpec = tween(durationMillis = 180),
        label = "${title}Elevation",
    )
    val shape = RoundedCornerShape(22.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = elevation.dp,
                shape = shape,
                ambientColor = glowColor,
                spotColor = glowColor,
                clip = false,
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = if (hovered) palette.hoverGradient else palette.gradient,
                    start = Offset.Zero,
                    end = Offset(720f, 720f),
                ),
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = if (hovered) palette.hoverBorderColor else palette.borderColor,
                shape = shape,
            )
            .onPointerEvent(PointerEventType.Enter) {
                hovered = true
            }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = title,
                style = TextStyle(
                    color = palette.titleColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = subtitle,
                style = TextStyle(
                    color = palette.bodyColor,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                ),
            )
        }
    }
}

@Composable
private fun rememberFeedbackPopupPalette(): FeedbackPopupPalette {
    val isDarkTheme = JewelTheme.isDark
    return remember(isDarkTheme) {
        if (isDarkTheme) {
            FeedbackPopupPalette(
                background = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0D1117),
                        Color(0xFF121926),
                        Color(0xFF111827),
                    ),
                ),
                borderColor = Color(0xFF2D3A51),
                accentColor = Color(0xFF95C4FF),
                titleColor = Color(0xFFF6F8FC),
                bodyColor = Color(0xFFB8C4D7),
                hintColor = Color(0xFF8F9DB4),
                buttonGlowColor = Color(0x663B82F6),
            )
        } else {
            FeedbackPopupPalette(
                background = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFFFFEFF),
                        Color(0xFFF6FAFF),
                        Color(0xFFEEF5FF),
                    ),
                ),
                borderColor = Color(0xFFD1DEF5),
                accentColor = Color(0xFF2F6FEB),
                titleColor = Color(0xFF10213A),
                bodyColor = Color(0xFF46566F),
                hintColor = Color(0xFF61728E),
                buttonGlowColor = Color(0x332F6FEB),
            )
        }
    }
}

@Composable
private fun rememberBugButtonPalette(): FeedbackActionPalette {
    val isDarkTheme = JewelTheme.isDark
    return remember(isDarkTheme) {
        if (isDarkTheme) {
            FeedbackActionPalette(
                gradient = listOf(Color(0xFFFF7A7A), Color(0xFFF04F74)),
                hoverGradient = listOf(Color(0xFFFF8C80), Color(0xFFFF5B7F)),
                borderColor = Color(0x66FFF1F2),
                hoverBorderColor = Color(0xB3FFF7F8),
                titleColor = Color.White,
                bodyColor = Color(0xFFFDEDEE),
            )
        } else {
            FeedbackActionPalette(
                gradient = listOf(Color(0xFFFF8F7E), Color(0xFFFF5D7A)),
                hoverGradient = listOf(Color(0xFFFF9C8C), Color(0xFFFF6783)),
                borderColor = Color(0x80FFF6F7),
                hoverBorderColor = Color.White,
                titleColor = Color.White,
                bodyColor = Color(0xFFFFF4F4),
            )
        }
    }
}

@Composable
private fun rememberFeedbackButtonPalette(): FeedbackActionPalette {
    val isDarkTheme = JewelTheme.isDark
    return remember(isDarkTheme) {
        if (isDarkTheme) {
            FeedbackActionPalette(
                gradient = listOf(Color(0xFF4F9FFF), Color(0xFF356CFF)),
                hoverGradient = listOf(Color(0xFF62ACFF), Color(0xFF4278FF)),
                borderColor = Color(0x66F0F7FF),
                hoverBorderColor = Color(0xB3F5F9FF),
                titleColor = Color.White,
                bodyColor = Color(0xFFF1F6FF),
            )
        } else {
            FeedbackActionPalette(
                gradient = listOf(Color(0xFF58A6FF), Color(0xFF2F6FEB)),
                hoverGradient = listOf(Color(0xFF68B0FF), Color(0xFF3A78F2)),
                borderColor = Color(0x80F3F8FF),
                hoverBorderColor = Color.White,
                titleColor = Color.White,
                bodyColor = Color(0xFFF4F8FF),
            )
        }
    }
}

@Composable
private fun rememberMarketplaceButtonPalette(): FeedbackActionPalette {
    val isDarkTheme = JewelTheme.isDark
    return remember(isDarkTheme) {
        if (isDarkTheme) {
            FeedbackActionPalette(
                gradient = listOf(Color(0xFF3CCB7F), Color(0xFF1D9A68)),
                hoverGradient = listOf(Color(0xFF4FD68E), Color(0xFF28AA75)),
                borderColor = Color(0x66ECFFF5),
                hoverBorderColor = Color(0xB3F3FFF8),
                titleColor = Color.White,
                bodyColor = Color(0xFFEFFFF6),
            )
        } else {
            FeedbackActionPalette(
                gradient = listOf(Color(0xFF3FBF75), Color(0xFF16865B)),
                hoverGradient = listOf(Color(0xFF4ECB83), Color(0xFF219667)),
                borderColor = Color(0x80F2FFF7),
                hoverBorderColor = Color.White,
                titleColor = Color.White,
                bodyColor = Color(0xFFF2FFF7),
            )
        }
    }
}
