package org.jetbrains.hexana.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.jewel.foundation.theme.JewelTheme

object HexanaTheme {
    val backgroundColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color.Black else Color.White

    val textColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color.White else Color.Black

    val hexTextColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color.Gray else Color.DarkGray

    val hexCellActiveColor: Color
        @Composable
        get() = Color.Yellow

    val hexCellHoverActiveColor: Color
        @Composable
        get() = Color(98, 100, 124, 255)

    val hexCellHoverColor: Color
        @Composable
        get() = Color(255, 175, 42, 255)
}