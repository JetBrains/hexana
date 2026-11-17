package org.jetbrains.hexana.vscode

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hex viewer's scroll model on a real scroll container: native up to [MAX_SCROLL_PX] of rows,
 * scaled beyond, where a browser can no longer lay out one pixel per pixel.
 */
class HexScrollerTest {
    private val viewport = 420 // header row + 20 rows

    private fun container(contentPx: Double): HTMLElement {
        val outer = document.createElement("div") as HTMLElement
        outer.style.height = "${viewport}px"
        outer.style.overflowY = "scroll"
        val header = document.createElement("div") as HTMLElement
        header.style.height = "${ROW_HEIGHT_PX}px"
        val spacer = document.createElement("div") as HTMLElement
        spacer.style.height = "${contentPx}px"
        outer.appendChild(header)
        outer.appendChild(spacer)
        document.body!!.appendChild(outer)
        return outer
    }

    @AfterTest
    fun cleanUp() {
        document.body!!.innerHTML = ""
    }

    private fun scroller(totalRows: Long): HexScroller {
        val scroller = HexScroller()
        scroller.totalRows = totalRows
        val element = container(scroller.spacerPx)
        scroller.container = element
        scroller.onScroll(0.0, element.clientHeight)
        return scroller
    }

    @Test
    fun smallFilesScrollNatively() {
        val scroller = scroller(totalRows = 10_000)
        assertFalse(scroller.scaled)
        assertEquals(20L, scroller.visibleRows)
        scroller.scrollToRow(500)
        scroller.onScroll(scroller.container!!.scrollTop, viewport)
        assertEquals(500L, scroller.topRow)
        assertEquals(500.0 * ROW_HEIGHT_PX, scroller.container!!.scrollTop)
    }

    @Test
    fun largeFilesScaleTheScrollbarButMoveByWholeRows() {
        // 5 GiB at 16 bytes per row.
        val totalRows = 5L * 1024 * 1024 * 1024 / 16
        val scroller = scroller(totalRows)
        assertTrue(scroller.scaled)
        assertEquals(MAX_SCROLL_PX, scroller.spacerPx)

        // Jumping to a row far past what native scrolling could reach keeps the exact row,
        // even though the scaled pixel position cannot express it.
        val target = 300_000_123L
        scroller.scrollToRow(target)
        scroller.onScroll(scroller.container!!.scrollTop, viewport) // our own scroll event
        assertEquals(target, scroller.topRow)

        // The wheel moves by rows, not by scaled pixels.
        scroller.onWheel(3.0 * ROW_HEIGHT_PX, 0)
        assertEquals(target + 3, scroller.topRow)

        // Dragging the scrollbar to the bottom shows the last rows.
        val element = scroller.container!!
        element.scrollTop = (element.scrollHeight - element.clientHeight).toDouble()
        scroller.onScroll(element.scrollTop, viewport)
        assertEquals(scroller.maxTopRow, scroller.topRow)
        assertEquals(totalRows - 1, scroller.rowAt((viewport - ROW_HEIGHT_PX - 1).toDouble()))

        // A row out of view is brought in with the least movement.
        scroller.scrollToRow(1_000)
        scroller.ensureRowVisible(1_030)
        assertEquals(1_030L - scroller.visibleRows + 1, scroller.topRow)
    }
}
