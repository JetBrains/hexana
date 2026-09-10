package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * Folds [tabs] behind a single parent tab named [name], switching between them with an inner tab
 * strip.
 *
 * The strip mirrors `GroupedTableTabContent`, but over arbitrary [PanelTab]s rather than table
 * descriptors, and it follows the IntelliJ plugin's `groupPanels` rule rather than the
 * grouped-table one: a lone tab is returned **unchanged**, keeping its own name, instead of being
 * promoted to `"$name: ${tab.label}"`. Otherwise a wasm with a single DWARF section would show
 * `DWARF: .debug_info` here and `.debug_info` in IntelliJ.
 */
internal fun groupPanelTabs(name: String, tabs: List<PanelTab>): List<PanelTab> = when {
    tabs.isEmpty() -> emptyList()
    tabs.size == 1 -> tabs
    else -> listOf(PanelTab(name, padding = false) { selection -> GroupedPanelTabContent(tabs, selection) })
}

@Composable
private fun GroupedPanelTabContent(tabs: List<PanelTab>, selection: HexSelectionState) {
    var selectedIndex by remember(tabs) { mutableStateOf(0) }

    // A flex column with a zero min-height: a VirtualizedList child takes its height from the
    // parent's remaining space, and without these it collapses to nothing.
    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "0")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
                property("flex", "0 0 auto")
            }
        }) {
            for ((index, tab) in tabs.withIndex()) {
                val isActive = index == selectedIndex
                Div({
                    style {
                        property("padding", "4px 12px")
                        property("cursor", "pointer")
                        property("font-size", "12px")
                        property("user-select", "none")
                        property("white-space", "nowrap")
                        property(
                            "border-bottom",
                            if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent",
                        )
                        property(
                            "color",
                            if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))",
                        )
                    }
                    onClick { selectedIndex = index }
                }) {
                    Text(tab.label)
                }
            }
        }

        val current = tabs.getOrNull(selectedIndex) ?: return@Div
        Div({
            style {
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
                property("flex", "1")
                property("min-height", "0")
                if (current.padding) property("padding", "8px")
            }
        }) {
            current.content(selection)
        }
    }
}
