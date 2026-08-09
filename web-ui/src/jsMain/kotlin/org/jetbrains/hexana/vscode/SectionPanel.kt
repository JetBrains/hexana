package org.jetbrains.hexana.vscode

/**
 * Scrolls the hex viewer container so the given byte offset is visible.
 */
fun scrollHexViewerTo(byteOffset: Int) {
    val row = byteOffset / 16
    val targetScrollTop = row * ROW_HEIGHT_PX
    val container = kotlinx.browser.document.querySelector(".hex-viewer-scroll") as? org.w3c.dom.HTMLElement ?: return
    // Skip scrolling when the target row is already visible in the viewport.
    val currentTop = container.scrollTop
    val viewportHeight = container.clientHeight.toDouble()
    if (targetScrollTop >= currentTop && targetScrollTop + ROW_HEIGHT_PX <= currentTop + viewportHeight) return
    container.scrollTop = targetScrollTop.toDouble()
}
