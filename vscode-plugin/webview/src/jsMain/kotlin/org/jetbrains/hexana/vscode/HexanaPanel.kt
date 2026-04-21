package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType

enum class HexanaTab(val label: String) {
    Summary("Summary"),
    Exports("Exports"),
    Imports("Imports"),
    Modules("Modules"),
    Wat("WAT"),
}

@Composable
fun HexanaPanel(
    bytes: ByteArray,
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
    fileName: String,
) {
    val visibleTabs = remember(providerType) {
        buildList {
            add(HexanaTab.Summary)
            add(HexanaTab.Exports)
            add(HexanaTab.Imports)
            if (providerType is BinaryProviderType.WasmComponent) {
                add(HexanaTab.Modules)
            }
            add(HexanaTab.Wat)
        }
    }

    var activeTab by remember { mutableStateOf(HexanaTab.Summary) }

    // If the active tab was removed (e.g. switching from component to core), reset
    if (activeTab !in visibleTabs) {
        activeTab = HexanaTab.Summary
    }

    Div({
        classes("hexana-panel")
        style {
            property("border-top", "1px solid var(--hexana-border, #333)")
            property("flex", "1 1 0%")
            property("min-height", "120px")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("overflow", "hidden")
        }
    }) {
        // Tab bar
        Div({
            classes("hexana-tab-bar")
            style {
                display(DisplayStyle.Flex)
                property("align-items", "center")
                property("gap", "0")
                property("flex-shrink", "0")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
                property("background", "var(--hexana-tab-bg, rgba(255,255,255,0.03))")
            }
        }) {
            for (tab in visibleTabs) {
                val isActive = tab == activeTab
                Div({
                    classes("hexana-tab")
                    if (isActive) classes("hexana-tab-active")
                    style {
                        property("padding", "6px 16px")
                        property("cursor", "pointer")
                        property("font-size", "12px")
                        property("user-select", "none")
                        property("border-bottom", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                        property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                        property("background", if (isActive) "var(--hexana-tab-active-bg, transparent)" else "transparent")
                    }
                    onClick { activeTab = tab }
                }) {
                    Text(tab.label)
                }
            }
        }

        // Tab content
        Div({
            classes("hexana-tab-content")
            style {
                property("overflow-y", "auto")
                property("padding", "8px 12px")
                property("flex", "1")
                property("min-height", "0")
            }
        }) {
            when (activeTab) {
                HexanaTab.Summary -> SummaryTabContent(buffer, providerType, selection)
                HexanaTab.Exports -> ExportsTabContent(buffer, providerType, selection)
                HexanaTab.Imports -> ImportsTabContent(buffer, providerType, selection)
                HexanaTab.Modules -> ModulesTabContent(buffer, providerType, selection, fileName)
                HexanaTab.Wat -> WatTabContent(bytes, buffer, providerType)
            }
        }
    }
}
