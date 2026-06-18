package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.events.Event
import kotlin.math.max
import kotlin.math.min

/**
 * A virtualized list that only renders the DOM elements currently visible
 * in the scroll viewport, plus a small overscan buffer. This avoids
 * creating thousands of DOM nodes for large data sets (class lists,
 * string tables, constant pools).
 *
 * @param itemCount total number of items in the list.
 * @param itemHeightPx fixed height of each item in pixels.
 * @param overscan number of extra items to render above/below the viewport.
 * @param content composable that renders a single item at the given index.
 */
@Composable
internal fun VirtualizedList(
    itemCount: Int,
    itemHeightPx: Int,
    overscan: Int = 5,
    content: @Composable (index: Int) -> Unit,
) {
    val totalHeight = itemCount.toLong() * itemHeightPx
    var scrollTop by remember { mutableStateOf(0.0) }
    // Default to a reasonable viewport so the first paint shows rows even
    // before the browser has laid out the container.
    var viewportHeight by remember { mutableStateOf(600.0) }

    Div({
        style {
            property("overflow-y", "auto")
            property("flex", "1")
            property("min-height", "0")
            position(Position.Relative)
        }
        ref { element ->
            // Measure once the element is attached to the DOM.
            val measured = element.clientHeight.toDouble()
            if (measured > 0) viewportHeight = measured

            val scrollListener: (Event) -> Unit = {
                scrollTop = element.scrollTop
                val h = element.clientHeight.toDouble()
                if (h > 0) viewportHeight = h
            }
            element.addEventListener("scroll", scrollListener)

            // Schedule a deferred measurement after the browser finishes layout,
            // so we get the real height even if clientHeight was 0 in ref.
            val rafId = kotlinx.browser.window.requestAnimationFrame {
                val h = element.clientHeight.toDouble()
                if (h > 0) viewportHeight = h
            }

            onDispose {
                element.removeEventListener("scroll", scrollListener)
                kotlinx.browser.window.cancelAnimationFrame(rafId)
            }
        }
    }) {
        // Inner container whose full height drives the scrollbar.
        Div({
            style {
                height(totalHeight.px)
                position(Position.Relative)
            }
        }) {
            if (itemCount > 0 && itemHeightPx > 0) {
                val firstVisible = max(0, (scrollTop / itemHeightPx).toInt() - overscan)
                val lastVisible = min(itemCount - 1, ((scrollTop + viewportHeight) / itemHeightPx).toInt() + overscan)

                for (i in firstVisible..lastVisible) {
                    val topPx = i.toLong() * itemHeightPx
                    Div({
                        style {
                            position(Position.Absolute)
                            property("top", "${topPx}px")
                            property("left", "0")
                            property("right", "0")
                            height(itemHeightPx.px)
                        }
                    }) {
                        content(i)
                    }
                }
            }
        }
    }
}
