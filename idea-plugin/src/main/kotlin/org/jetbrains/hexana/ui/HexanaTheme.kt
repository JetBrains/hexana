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

    val searchResultColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(153, 115, 0) else Color(255, 235, 59)

    val garbageHighlightColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(255, 96, 96, 48) else Color(255, 60, 60, 40)
}