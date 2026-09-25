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

    /** Standard-diff line background for added lines (translucent green, theme-aware). */
    val diffAddedColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(46, 160, 67, 70) else Color(46, 160, 67, 55)

    /** Standard-diff line background for removed lines (translucent red, theme-aware). */
    val diffRemovedColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(248, 81, 73, 70) else Color(248, 81, 73, 55)

    /** Diff row tint for modified entities (translucent amber, theme-aware). */
    val diffModifiedColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(210, 153, 34, 70) else Color(212, 167, 44, 60)

    /** Diff row tint for moved entities (translucent blue, theme-aware). */
    val diffMovedColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(56, 139, 253, 70) else Color(56, 139, 253, 55)

    val editedByteForegroundColor: Color
        @Composable
        get() = if (JewelTheme.isDark) Color(255, 165, 0) else Color(200, 90, 0)

    /**
     * Very subtle, alternating background tints used to distinguish top-level
     * structural regions (sections/segments) in the hex view. The palette
     * cycles so adjacent regions always differ. Alpha is kept extremely low
     * to avoid visual distraction.
     */
    val structureColors: List<Color>
        @Composable
        get() = if (JewelTheme.isDark) darkStructureColors else lightStructureColors

    /**
     * Section tints for the hex diff. Unlike [structureColors] they avoid the diff's own
     * hues — amber (modified), red (removed), green (added) — and keep to blues, purples,
     * teal and slate, so a tinted section never reads as a change.
     */
    val diffStructureColors: List<Color>
        @Composable
        get() = if (JewelTheme.isDark) darkDiffStructureColors else lightDiffStructureColors

    private val darkDiffStructureColors = listOf(
        Color(100, 149, 237, 44),  // cornflower blue
        Color(186, 147, 214, 44),  // lavender
        Color(90, 200, 215, 44),   // cyan teal
        Color(150, 160, 190, 44),  // slate
        Color(125, 115, 235, 44),  // indigo
    )

    private val lightDiffStructureColors = listOf(
        Color(70, 130, 220, 40),   // cornflower blue
        Color(148, 103, 189, 40),  // muted purple
        Color(40, 160, 190, 40),   // cyan teal
        Color(105, 120, 155, 40),  // slate
        Color(95, 85, 210, 40),    // indigo
    )

    private val darkStructureColors = listOf(
        Color(100, 149, 237, 38),  // cornflower blue
        Color(144, 238, 144, 38),  // light green
        Color(255, 182, 108, 38),  // peach
        Color(186, 147, 214, 38),  // lavender
        Color(255, 218, 120, 38),  // pale gold
        Color(120, 210, 210, 38),  // teal
    )

    private val lightStructureColors = listOf(
        Color(70, 130, 220, 40),   // cornflower blue
        Color(60, 179, 113, 40),   // medium sea green
        Color(230, 140, 60, 40),   // sandy orange
        Color(148, 103, 189, 40),  // muted purple
        Color(200, 170, 50, 40),   // dark gold
        Color(60, 180, 180, 40),   // teal
    )
}