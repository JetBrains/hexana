package org.jetbrains.hexana.vscode

import org.w3c.dom.HTMLElement

/**
 * Calls [onResize] whenever [element]'s size changes (a `ResizeObserver`), and returns the function
 * that stops observing.
 *
 * Virtualized views read their viewport height and scroll offset into Compose state on `scroll`
 * events only, so a viewport that grows or shrinks without scrolling — the tabs panel re-docked,
 * expanded, or its divider dragged — left them rendering rows for a stale viewport.
 */
internal fun observeResize(element: HTMLElement, onResize: () -> Unit): () -> Unit {
    val callback: (dynamic) -> Unit = { onResize() }
    val observer: dynamic = js("new ResizeObserver(callback)")
    observer.observe(element)
    return { observer.disconnect() }
}
