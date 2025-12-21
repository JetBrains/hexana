package org.jetbrains.hexana.ui.panel

import androidx.compose.runtime.Composable
import org.jetbrains.hexana.HexViewStateHolder

class Panel(val description: String, val render: @Composable () -> Unit)

internal fun HexViewStateHolder.scrollHexViewTo(offset: Int, size: Int) {
    scrollHexViewTo(offset.toLong())

    val startLine = (offset / bytesPerRow.value)
    val startLineOffset = (offset % bytesPerRow.value)

    val endLine =
        ((offset + size) / bytesPerRow.value)
    val endLineOffset =
        ((offset + size) % bytesPerRow.value) - 1

    setSelection(
        startLine, startLineOffset,
        endLine, endLineOffset
    )
}
