package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.ui.VerticalSplitPanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Top-level view for a WASM module diff: a tab strip over the size-impact and
 * entity comparisons (top), with a bottom split that shows the side-by-side WAT
 * of the function selected in either table (rendered on demand — selecting a row
 * renders just that one function, so there is no upfront whole-module cost).
 * Hosted by [org.jetbrains.hexana.HexanaFileEditor] for a
 * [org.jetbrains.hexana.HexanaWasmDiffVirtualFile].
 */
@Composable
fun WasmDiffEditorView(
    leftBuffer: CommonByteBuffer,
    rightBuffer: CommonByteBuffer,
    leftName: String,
    rightName: String,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedFunction by remember { mutableStateOf<FunctionSelection?>(null) }
    var topFraction by remember { mutableFloatStateOf(0.6f) }

    val titles = listOf("Size Impact", "Entities")
    val tabs = titles.mapIndexed { index, title ->
        TabData.Default(
            selected = index == selectedTab,
            content = { Text(title) },
            closable = false,
            onClick = { selectedTab = index },
        )
    }

    VerticalSplitPanel(
        topFraction = topFraction,
        onDragFraction = { delta -> topFraction = (topFraction + delta).coerceIn(0.2f, 0.9f) },
        northPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
                Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> WasmSizeDiffPanel(leftBuffer, rightBuffer, leftName, rightName) { selectedFunction = it }
                        else -> WasmEntityDiffPanel(leftBuffer, rightBuffer, leftName, rightName) { selectedFunction = it }
                    }
                }
            }
        },
        southPanel = {
            WasmFunctionWatDetailPanel(selectedFunction, leftBuffer, rightBuffer)
        },
    )
}
