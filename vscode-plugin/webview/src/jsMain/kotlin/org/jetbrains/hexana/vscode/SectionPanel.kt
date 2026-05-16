package org.jetbrains.hexana.vscode

/**
 * Scrolls the hex viewer container so the given byte offset is visible.
 */
internal fun scrollHexViewerTo(byteOffset: Int) {
    val row = byteOffset / 16
    val targetScrollTop = row * ROW_HEIGHT_PX
    val container = kotlinx.browser.document.querySelector(".hex-viewer-scroll") as? org.w3c.dom.HTMLElement
    container?.scrollTop = targetScrollTop.toDouble()
}
