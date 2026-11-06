package org.jetbrains.hexana.vscode

/** Scrolls the hex viewer so the given byte offset is visible, if it is not already. */
fun scrollHexViewerTo(byteOffset: Long) = HexViewerScrollRequests.request(byteOffset)

fun scrollHexViewerTo(byteOffset: Int) = scrollHexViewerTo(byteOffset.toLong())
