package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
) {
    if (!showSouthPanel) {
        Box(modifier = Modifier.fillMaxSize()) { northPanel() }
        return
    }
    // Delegate to the stateless core; the state holder is read live inside the
    // delta callback so divider drags stay correct across recomposition.
    VerticalSplitPanel(
        topFraction = state.topFraction,
        onDragFraction = { delta ->
            state.setTopFraction((state.topFraction + delta).coerceIn(MIN_TOP_FRACTION, 1f - MIN_BOTTOM_FRACTION))
        },
        northPanel = northPanel,
        southPanel = southPanel,
    )
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

/** Minimum fraction reserved for the top pane so the splitter cannot be dragged to the very top. */
private const val MIN_TOP_FRACTION = 0.2f

/** Minimum fraction reserved for the bottom pane so the splitter cannot be dragged to the very bottom. */
private const val MIN_BOTTOM_FRACTION = 0.1f
