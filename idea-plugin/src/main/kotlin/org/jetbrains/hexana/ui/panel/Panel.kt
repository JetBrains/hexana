package org.jetbrains.hexana.ui.panel

import androidx.compose.runtime.Composable
import org.jetbrains.hexana.HexViewStateHolder

class Panel(val description: String, val render: @Composable () -> Unit)

const val universalQualifiedNameMeasure = "component:subtractor11111/environent@2.9.0"
const val universalNameMeasure = "sdgdsgsd_DSgsdgdsg_DS_gsdgsdGS_GSD_GSD_GDSG__sgdsg_SDG_SDGsdgs_S_G"

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
