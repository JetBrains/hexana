package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.shared.WasmComponentTableTabsProvider
import org.jetbrains.hexana.shared.WasmTableTabsProvider
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmValueType

/**
 * Identifiers for non-table tabs that remain hardcoded outside the
 * [org.jetbrains.hexana.shared.TableTabsProvider] abstraction.
 */
private enum class FixedTab(val label: String) {
    Summary("Sections"),
    Size("Top"),
    Monos("Monos"),
    Garbage("Garbage"),
    Wat("WAT"),
}

/**
 * A unified tab entry that can be either a provider-generated table tab or a
 * fixed (hardcoded) tab.
 */
private class PanelTab(
    val label: String,
    val content: @Composable (HexSelectionState) -> Unit,
)

@Composable
fun HexanaPanel(
    provider: BinaryProvider,
    selection: HexSelectionState,
    fileName: String,
    heightPx: Double = 250.0,
    activeTab: String = FixedTab.Summary.label,
    onActiveTabChange: (String) -> Unit = {},
) {
    // -----------------------------------------------------------------------
    // Dialog state for run actions (core Wasm)
    // -----------------------------------------------------------------------
    var pendingWasmExport by remember { mutableStateOf<WasmFunctionExportRecordEntry?>(null) }
    var pendingWasmParams by remember { mutableStateOf<List<WasmValueType<*>>>(emptyList()) }

    // -----------------------------------------------------------------------
    // Dialog state for run actions (component model)
    // -----------------------------------------------------------------------
    var pendingProgramArgsExport by remember { mutableStateOf<WasmComponentExport?>(null) }
    var pendingFuncExport by remember { mutableStateOf<WasmComponentExport?>(null) }
    var pendingFuncInvokeName by remember { mutableStateOf("") }
    var pendingFuncArgs by remember { mutableStateOf<List<FunctionArgDescriptor>>(emptyList()) }

    // -----------------------------------------------------------------------
    // Build the tab list: provider-generated table tabs + fixed tabs
    // -----------------------------------------------------------------------
    val tabs = remember(provider, fileName) {
        buildList {
            // Summary is always first
            add(PanelTab(FixedTab.Summary.label) { sel ->
                SummaryTabContent(provider, sel)
            })

            /*val commonTabs = TableTabsProviderRegistry.providers.flatMap { p ->
                p.tableTabs(provider)
            }*/

            // Provider-generated table tabs
            when (provider) {
                is WasmBinaryProvider -> {
                    if (provider.canProvideWasmModule()) {
                        val tableTabs = WasmTableTabsProvider().toTableTabs(
                            provider = provider,
                        ) { descriptors ->
                            descriptors.enrichWasmTabs(
                                provider = provider,
                                fileName = fileName,
                                onPendingWasmArgs = { export, params ->
                                    pendingWasmExport = export
                                    pendingWasmParams = params
                                },
                            )
                        }
                        addAll(tableTabs.map { tt -> PanelTab(tt.name) { sel -> tt.content(sel) } })
                    }
                }
                is WasmComponentBinaryProvider -> {
                    if (provider.canProvideWasmModule()) {
                        val tableTabs = WasmComponentTableTabsProvider().toTableTabs(
                            provider = provider,
                        ) { descriptors ->
                            descriptors.enrichComponentTabs(
                                provider = provider,
                                fileName = fileName,
                                onPendingProgramArgs = { export ->
                                    pendingProgramArgsExport = export
                                },
                                onPendingComponentArgs = { invokeName, args, export ->
                                    pendingFuncExport = export
                                    pendingFuncInvokeName = invokeName
                                    pendingFuncArgs = args
                                },
                            )
                        }
                        addAll(tableTabs.map { tt -> PanelTab(tt.name) { sel -> tt.content(sel) } })
                    }
                }
                else -> {}
            }

            // Size tab (always present for Wasm and component)
            if (provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.Size.label) { sel ->
                    SizeTabContent(provider, sel)
                })
            }

            // Monos and Garbage (core Wasm only)
            if (provider is WasmBinaryProvider) {
                add(PanelTab(FixedTab.Monos.label) { sel ->
                    MonosTabContent(provider, sel)
                })
                add(PanelTab(FixedTab.Garbage.label) { sel ->
                    GarbageTabContent(provider, sel)
                })
            }

            // WAT tab (always present for Wasm and component)
            if (provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.Wat.label) { _ ->
                    WatTabContent(provider)
                })
            }
        }
    }

    // If the active tab was removed (e.g. switching from component to core), reset
    if (tabs.none { it.label == activeTab }) {
        onActiveTabChange(FixedTab.Summary.label)
    }

    // -----------------------------------------------------------------------
    // Core Wasm function args dialog
    // -----------------------------------------------------------------------
    val wasmPending = pendingWasmExport
    if (wasmPending != null && pendingWasmParams.isNotEmpty()) {
        FunctionArgsDialog(
            exportName = wasmPending.name,
            args = wasmArgDescriptors(pendingWasmParams),
            isComponent = false,
            onConfirm = { result ->
                val programArgs = result.args.joinToString(" ")
                postRunExportToHost(
                    exportName = wasmPending.name,
                    invokeArgs = "--invoke ${wasmPending.name}",
                    programArgs = programArgs,
                    wasmBytes = provider.bytes.toByteArray(),
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingWasmExport = null
                pendingWasmParams = emptyList()
            },
            onCancel = {
                pendingWasmExport = null
                pendingWasmParams = emptyList()
            },
        )
    }

    // -----------------------------------------------------------------------
    // Component program args dialog (wasi:cli/run)
    // -----------------------------------------------------------------------
    val programArgsPending = pendingProgramArgsExport
    if (programArgsPending != null) {
        ProgramArgsDialog(
            exportName = programArgsPending.exportName,
            isComponent = true,
            onConfirm = { result ->
                postRunExportToHost(
                    exportName = programArgsPending.exportName,
                    isComponent = true,
                    programArgs = result.programArgs,
                    wasmBytes = provider.bytes.toByteArray(),
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingProgramArgsExport = null
            },
            onCancel = { pendingProgramArgsExport = null },
        )
    }

    // -----------------------------------------------------------------------
    // Component function args dialog
    // -----------------------------------------------------------------------
    val funcPending = pendingFuncExport
    if (funcPending != null && pendingFuncArgs.isNotEmpty()) {
        FunctionArgsDialog(
            exportName = pendingFuncInvokeName,
            args = pendingFuncArgs,
            isComponent = true,
            onConfirm = { result ->
                val argsString = result.args.joinToString(", ")
                postRunExportToHost(
                    exportName = funcPending.exportName,
                    isComponent = true,
                    invokeArgs = "--invoke \"${pendingFuncInvokeName}($argsString)\"",
                    wasmBytes = provider.bytes.toByteArray(),
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingFuncExport = null
                pendingFuncInvokeName = ""
                pendingFuncArgs = emptyList()
            },
            onCancel = {
                pendingFuncExport = null
                pendingFuncInvokeName = ""
                pendingFuncArgs = emptyList()
            },
        )
    }

    // -----------------------------------------------------------------------
    // Panel layout
    // -----------------------------------------------------------------------
    Div({
        classes("hexana-panel")
        style {
            property("border-top", "1px solid var(--hexana-border, #333)")
            property("flex", "0 0 auto")
            height(heightPx.px)
            property("min-height", "80px")
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
            for (tab in tabs) {
                val isActive = tab.label == activeTab
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
                    onClick {
                        onActiveTabChange(tab.label)
                        postTabSelectedToHost(tab.label)
                    }
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
            val currentTab = tabs.firstOrNull { it.label == activeTab }
            currentTab?.content?.invoke(selection)
        }
    }
}
