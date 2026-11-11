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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
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
    // The panes are movable content: the three layouts below call them from different places, and
    // plain calls would dispose and recreate each pane — hex scroll position, folds, table state,
    // running effects — whenever the tabs are docked elsewhere or appear. The latest lambdas are
    // read through rememberUpdatedState, so the remembered wrappers never go stale.
    val currentNorth by rememberUpdatedState(northPanel)
    val currentSouth by rememberUpdatedState(southPanel)
    val north = remember { movableContentOf { currentNorth() } }
    val south = remember { movableContentOf { currentSouth() } }

    if (!showSouthPanel) {
        Box(modifier = Modifier.fillMaxSize()) { north() }
        return
    }
    // Delegate to the stateless core; the state holder is read live inside the
    // delta callback so divider drags stay correct across recomposition.
    if (sideBySide) {
        // No fractional minimum: the hex pane may shrink to the collapsed strip (expand button),
        // which the split itself keeps at COLLAPSED_PANE_WIDTH.
        HorizontalSplitPanel(
            leftFraction = state.topFraction,
            onDragFraction = { delta ->
                state.setTopFraction((state.topFraction + delta).coerceIn(0f, 1f - MIN_BOTTOM_FRACTION))
            },
            westPanel = north,
            eastPanel = south,
        )
    } else {
        VerticalSplitPanel(
            topFraction = state.topFraction,
            onDragFraction = { delta ->
                state.setTopFraction((state.topFraction + delta).coerceIn(MIN_TOP_FRACTION, 1f - MIN_BOTTOM_FRACTION))
            },
            northPanel = north,
            southPanel = south,
        )
    }
}

/**
 * State-hoisted horizontal split, the side-by-side counterpart of the vertical one:
 * [leftFraction] is the west pane's width fraction, [onDragFraction] receives incremental
 * changes from a divider drag.
 *
 * The west pane never gets narrower than [COLLAPSED_PANE_WIDTH]: a fraction below that (the
 * tabs panel's expand button sets 0) shows it as a thin, clipped strip — just enough to grab
 * the divider and drag it back. A drag from there starts at the strip's edge, not at 0.
 */
@Composable
fun HorizontalSplitPanel(
    leftFraction: Float,
    onDragFraction: (deltaFraction: Float) -> Unit,
    westPanel: @Composable () -> Unit,
    eastPanel: @Composable () -> Unit,
) {
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val collapsedPx = with(LocalDensity.current) { COLLAPSED_PANE_WIDTH.toPx() }
    val collapsedFraction = if (containerWidthPx > 0) (collapsedPx / containerWidthPx).coerceAtMost(0.5f) else 0f
    val collapsed = leftFraction <= collapsedFraction
    // Read inside the drag callback, which is remembered across recompositions.
    val currentLeftFraction by rememberUpdatedState(leftFraction)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
    ) {
        val westModifier = if (collapsed) {
            Modifier.width(COLLAPSED_PANE_WIDTH)
        } else {
            Modifier.weight(leftFraction.coerceAtMost(1f - MIN_BOTTOM_FRACTION))
        }
        Box(modifier = Modifier.fillMaxHeight().then(westModifier).clipToBounds()) {
            westPanel()
        }

        VerticalDraggableDivider(
            onDrag = { deltaX ->
                if (containerWidthPx > 0) {
                    val minFraction = (collapsedPx / containerWidthPx).coerceAtMost(0.5f)
                    // Snap a collapsed pane to the strip's real fraction first, so the divider
                    // follows the pointer instead of sitting still until 0 catches up.
                    val snap = (minFraction - currentLeftFraction).coerceAtLeast(0f)
                    onDragFraction(deltaX / containerWidthPx + snap)
                }
            }
        )

        val eastWeight = if (collapsed) 1f else (1f - leftFraction).coerceAtLeast(MIN_BOTTOM_FRACTION)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(eastWeight)
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

/** Width the left pane collapses to when the tabs panel is expanded beside it; enough to grab the divider. */
private val COLLAPSED_PANE_WIDTH = 12.dp

/** Minimum fraction reserved for the top (or left) pane so the splitter cannot be dragged to the very edge. */
private const val MIN_TOP_FRACTION = 0.2f

/** Minimum fraction reserved for the bottom (or right) pane so the splitter cannot be dragged to the very edge. */
private const val MIN_BOTTOM_FRACTION = 0.1f
