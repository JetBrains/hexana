package org.jetbrains.hexana.ui.panel

import androidx.compose.runtime.Composable
import org.jetbrains.hexana.HexViewStateHolder

open class Panel(val description: String, val render: @Composable () -> Unit)

const val universalQualifiedNameMeasure = "component:subtractor11111/environent@2.9.0"
const val universalNameMeasure = "sdgdsgsd_DSgsdgdsg_DS_gsdgsdGS_GSD_GSD_GDSG__sgdsg_SDG_SDGsdgs_S_G"

internal fun HexViewStateHolder.scrollHexViewTo(offset: Int, size: Int) = scrollHexViewTo(offset.toLong(), size.toLong())

/** Scrolls to [offset] and selects the [size] bytes there (just the first byte when [size] is 0). */
internal fun HexViewStateHolder.scrollHexViewTo(offset: Long, size: Long) {
    scrollHexViewTo(offset)
    setSelection(offset..(offset + size - 1).coerceAtLeast(offset))
}
