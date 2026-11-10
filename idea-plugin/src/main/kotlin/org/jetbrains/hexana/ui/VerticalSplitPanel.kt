package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.jewel.ui.component.Divider
import java.awt.Cursor


@Composable
fun VerticalSplitPanel(
    state: HexViewStateHolder,
    northPanel: @Composable () -> Unit,
    southPanel: @Composable () -> Unit,
    /**
     * `false` gives [northPanel] the whole height and drops the divider — for a binary with
     * no tabs, where the south pane would only be empty space.
     */
    showSouthPanel: Boolean = true,
    /**
     * `true` puts [southPanel] to the right of [northPanel] instead of below it. Both layouts
     * share [HexViewStateHolder.topFraction] as the first pane's share, so toggling keeps the
     * proportion and "expand"/"restore" work the same either way.
     */
    sideBySide: Boolean = false,
) {
    if (!showSouthPanel) {
        Box(modifier = Modifier.fillMaxSize()) { northPanel() }
        return
    }
    // Delegate to the stateless core; the state holder is read live inside the
    // delta callback so divider drags stay correct across recomposition.
    val onDragFraction = { delta: Float ->
        state.setTopFraction((state.topFraction + delta).coerceIn(MIN_TOP_FRACTION, 1f - MIN_BOTTOM_FRACTION))
    }
    if (sideBySide) {
        HorizontalSplitPanel(
            leftFraction = state.topFraction,
            onDragFraction = onDragFraction,
            westPanel = northPanel,
            eastPanel = southPanel,
        )
    } else {
        VerticalSplitPanel(
            topFraction = state.topFraction,
            onDragFraction = onDragFraction,
            northPanel = northPanel,
            southPanel = southPanel,
        )
    }
}

/**
 * State-hoisted horizontal split, the side-by-side counterpart of the vertical one:
 * [leftFraction] is the west pane's width fraction, [onDragFraction] receives incremental
 * changes from a divider drag.
 */
@Composable
fun HorizontalSplitPanel(
    leftFraction: Float,
    onDragFraction: (deltaFraction: Float) -> Unit,
    westPanel: @Composable () -> Unit,
    eastPanel: @Composable () -> Unit,
) {
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val weightRange = MIN_TOP_FRACTION..(1f - MIN_BOTTOM_FRACTION)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
    ) {
        Box(modifier = Modifier.fillMaxHeight().weight(leftFraction.coerceIn(weightRange))) {
            westPanel()
        }

        VerticalDraggableDivider(
            onDrag = { deltaX ->
                if (containerWidthPx > 0) {
                    onDragFraction(deltaX / containerWidthPx)
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight((1f - leftFraction).coerceIn(weightRange))
                .background(HexanaTheme.backgroundColor),
            contentAlignment = Alignment.TopStart
        ) {
            eastPanel()
        }
    }
}

@Composable
private fun VerticalDraggableDivider(onDrag: (deltaX: Float) -> Unit) {
    val dragHitAreaThickness = 8.dp

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    onDrag(delta)
                },
                startDragImmediately = true
            )
    ) {
        Row {
            Divider(
                orientation = org.jetbrains.jewel.ui.Orientation.Vertical,
                modifier = Modifier
                    .fillMaxHeight().width(dragHitAreaThickness / 2).background(Color.Gray)
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(dragHitAreaThickness)
                    .background(Color.Transparent)
            )
        }
    }
}

/**
 * State-hoisted vertical split. [topFraction] is the north pane's height fraction;
 * [onDragFraction] receives the incremental fraction change from a divider drag —
 * the caller adds it to its own live state (avoids stale-capture in the remembered
 * drag lambda). Lets surfaces without a [HexViewStateHolder] (e.g. the WASM diff
 * editor) use the same split.
 */
@Composable
fun VerticalSplitPanel(
    topFraction: Float,
    onDragFraction: (deltaFraction: Float) -> Unit,
    northPanel: @Composable () -> Unit,
    southPanel: @Composable () -> Unit,
) {
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    val weightRange = MIN_TOP_FRACTION..(1f - MIN_BOTTOM_FRACTION)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(topFraction.coerceIn(weightRange))) {
            northPanel()
        }

        HorizontalDraggableDivider(
            onDrag = { deltaY ->
                if (containerHeightPx > 0) {
                    onDragFraction(deltaY / containerHeightPx)
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight((1f - topFraction).coerceIn(weightRange))
                .background(HexanaTheme.backgroundColor),
            contentAlignment = Alignment.TopStart
        ) {
            southPanel()
        }
    }
}

@Composable
private fun HorizontalDraggableDivider(onDrag: (deltaY: Float) -> Unit) {
    val dragHitAreaThickness = 8.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    onDrag(delta)
                },
                startDragImmediately = true
            )
    ) {
        Column {
            Divider(
                orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
                modifier = Modifier
                    .fillMaxWidth().height(dragHitAreaThickness / 2).background(Color.Gray)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dragHitAreaThickness)
                    .background(Color.Transparent)
            )
        }
    }
}

/** Minimum fraction reserved for the top (or left) pane so the splitter cannot be dragged to the very edge. */
private const val MIN_TOP_FRACTION = 0.2f

/** Minimum fraction reserved for the bottom (or right) pane so the splitter cannot be dragged to the very edge. */
private const val MIN_BOTTOM_FRACTION = 0.1f
