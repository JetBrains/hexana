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
    southPanel: @Composable () -> Unit
) {
    // Delegate to the stateless core; the state holder is read live inside the
    // delta callback so divider drags stay correct across recomposition.
    VerticalSplitPanel(
        topFraction = state.topFraction,
        onDragFraction = { delta -> state.setTopFraction(state.topFraction + delta) },
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

    val weightRange = 0.0001f..0.9999f

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

    Divider(
        orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
        modifier = Modifier
            .fillMaxWidth().height(dragHitAreaThickness / 2).background(Color.Gray)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dragHitAreaThickness)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .background(Color.Transparent)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    onDrag(delta)
                },
                startDragImmediately = true
            )

    )
}
