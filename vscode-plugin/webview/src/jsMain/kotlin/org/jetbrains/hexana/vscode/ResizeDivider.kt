package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

/**
 * A thin horizontal divider that can be dragged vertically to resize
 * the panels above and below it.
 *
 * [onDrag] is called with the vertical pixel delta from the drag start
 * position each time the mouse moves while the divider is being dragged.
 */
@Composable
internal fun ResizeDivider(onDrag: (deltaY: Double) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var startY by remember { mutableStateOf(0.0) }

    DisposableEffect(dragging) {
        if (!dragging) return@DisposableEffect onDispose {}

        val moveHandler: (Event) -> Unit = { event ->
            val me = event as? MouseEvent
            if (me != null) {
                me.preventDefault()
                val delta = me.clientY.toDouble() - startY
                startY = me.clientY.toDouble()
                onDrag(delta)
            }
        }

        val upHandler: (Event) -> Unit = {
            dragging = false
        }

        window.addEventListener("mousemove", moveHandler)
        window.addEventListener("mouseup", upHandler)
        onDispose {
            window.removeEventListener("mousemove", moveHandler)
            window.removeEventListener("mouseup", upHandler)
        }
    }

    Div({
        classes("hexana-resize-divider")
        style {
            property("flex-shrink", "0")
            height(5.px)
            property("cursor", "row-resize")
            property("background", "transparent")
            property("position", "relative")
            property("z-index", "2")
            // Make the hit target a bit larger than what is visually shown
            property("margin", "-2px 0")
            property("padding", "2px 0")
        }
        onMouseDown { event ->
            if (event.nativeEvent.asDynamic().button as Int != 0) return@onMouseDown
            event.preventDefault()
            startY = (event.nativeEvent as MouseEvent).clientY.toDouble()
            dragging = true
        }
    }) {
        // Visual line in the center of the hit area
        Div({
            classes("hexana-resize-divider-line")
            style {
                height(1.px)
                width(100.percent)
                property("background", "var(--hexana-border, #333)")
                property("position", "absolute")
                property("top", "50%")
                property("left", "0")
                property("transform", "translateY(-50%)")
            }
        })
    }
}
