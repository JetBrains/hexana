package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode

@Composable
fun SizeTabContent(
    provider: BinaryProvider,
    selection: HexSelectionState,
) {
    val root = remember(provider) { provider.sizeAnalysis }

    if (root == null) {
        Div({ classes("empty-hint") }) {
            Text("Size analysis unavailable.")
        }
        return
    }

    SizeNodeTable(root, selection)
}

@Composable
private fun SizeNodeTable(root: BinarySizeNode, selection: HexSelectionState) {
    Div({ style { property("margin-bottom", "8px"); property("font-size", "12px") } }) {
        Text("Total: ${formatSize(root.size)}")
    }

    if (root.children.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No size data available.")
        }
        return
    }

    SearchableTable(
        headers = listOf("ID", "Section", "Size", "%", "Offset"),
        columnAligns = listOf("right", "left", "right", "right", "right"),
        defaultSortColumn = 2,
        defaultSortDirection = SortDirection.DESC,
    ) {
        for ((index, child) in root.children.withIndex()) {
            val pct = child.percentage(root.size)
            val pctStr = "${((pct * 10).toInt() / 10)}.${((pct * 10).toInt() % 10)}%"
            val hex = "0x${child.offset.toString(16).uppercase()}"

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == child.offset &&
                    selection.selectionEnd == child.offset + child.size - 1,
                onClick = {
                    if (child.size > 0) {
                        selection.anchorIndex = child.offset
                        selection.activeIndex = child.offset + child.size - 1
                        selection.cursorIndex = child.offset
                        scrollHexViewerTo(child.offset)
                    }
                },
            ) {
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({ classes("cell") }) { Text(child.name) }
                Td({ classes("cell-right") }) {
                    Text(formatSize(child.size))
                }
                Td({ classes("cell-right") }) {
                    Text(pctStr)
                }
                Td({ classes("cell-right-muted") }) {
                    Text(hex)
                }
            }
        }
    }
}
