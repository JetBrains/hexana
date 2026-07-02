package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TabPriority
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Identifiers for non-table tabs that remain hardcoded outside the
 * [org.jetbrains.hexana.shared.TableTabsProvider] abstraction.
 */
private enum class FixedTab(val label: String) {
    Summary("Sections"),
    Wat("WAT"),
}

/**
 * A unified tab entry that can be either a provider-generated table tab or a
 * fixed (hardcoded) tab.
 */
class PanelTab(
    val label: String,
    val padding: Boolean = true,
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
    extraTabs: List<PanelTab> = emptyList(),
) {
    val pendingRun = rememberPendingRunState()

    // -----------------------------------------------------------------------
    // Build the tab list: provider-generated table tabs + fixed tabs
    // -----------------------------------------------------------------------
    val tabs = remember(provider, fileName, extraTabs) {
        buildList {
            // Summary is always first
            if (provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.Summary.label) { sel ->
                    SummaryTabContent(provider, sel)
                })
            }

            // Collect ordinary and grouped tabs per provider, then sort by
            // priority (High first) while preserving provider order within
            // the same priority level.
            val enricher = provider.getTabsEnricher()
            val zipProvider = provider as? ZipBinaryProvider
            data class TabEntry(val tab: PanelTab, val priority: TabPriority, val providerIndex: Int)
            val tabEntries = mutableListOf<TabEntry>()
            for ((providerIndex, p) in TableTabsProviderRegistry.providers.withIndex()) {
                val enriched = enricher(p.tableTabs(provider), fileName, pendingRun)
                for (d in enriched) {
                    val desc = if (d.name == "Entries" && zipProvider != null) {
                        enrichZipEntriesTab(d, zipProvider.displayEntries)
                    } else d
                    tabEntries += TabEntry(
                        PanelTab(desc.name) { sel -> TableTabContent(desc, sel) },
                        desc.priority,
                        providerIndex,
                    )
                }
                for (grouped in p.groupedTabs(provider)) {
                    val inner = grouped.innerTabs
                    val panelTab: PanelTab? = when {
                        inner.isEmpty() && grouped.emptyMessage == null -> null
                        inner.isEmpty() -> PanelTab(grouped.name) { _ ->
                            org.jetbrains.compose.web.dom.Text(grouped.emptyMessage ?: "")
                        }
                        inner.size == 1 -> {
                            val single = inner.single()
                            val combinedName = "${grouped.name}: ${single.name}"
                            PanelTab(combinedName) { sel -> TableTabContent(single, sel) }
                        }
                        else -> PanelTab(grouped.name) { sel ->
                            GroupedTableTabContent(grouped, sel)
                        }
                    }
                    if (panelTab != null) {
                        tabEntries += TabEntry(panelTab, grouped.priority, providerIndex)
                    }
                }
            }
            tabEntries.sortWith(compareBy<TabEntry> { it.priority }.thenBy { it.providerIndex })
            val (lowEntries, normalEntries) = tabEntries.partition { it.priority == TabPriority.Low }
            addAll(normalEntries.map { it.tab })

            // WAT tab (always present for Wasm and component)
            if (provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.Wat.label, padding = false) { _ ->
                    WatTabContent(provider)
                })
            }

            // Extra tabs injected by the caller (e.g. format-specific tabs
            // for class files, DEX files, ZIP archives).
            addAll(extraTabs)

            // Low-priority tabs go after all other panels (including non-table tabs).
            addAll(lowEntries.map { it.tab })
        }
    }

    // If the active tab was removed (e.g. switching from component to core), reset
    // to the first available tab.  Use SideEffect so the state update happens after
    // composition — calling onActiveTabChange directly during composition can be
    // swallowed and leave the panel empty until an external event forces recomposition.
    val needsReset = tabs.none { it.label == activeTab } && tabs.isNotEmpty()
    if (needsReset) {
        SideEffect {
            onActiveTabChange(tabs.first().label)
        }
    }

    // -----------------------------------------------------------------------
    // Core Wasm function args dialog
    // -----------------------------------------------------------------------
    val wasmPending = pendingRun.wasmExport
    if (wasmPending != null) {
        FunctionArgsDialog(
            exportName = wasmPending.name,
            args = wasmArgDescriptors(pendingRun.wasmParams),
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
                pendingRun.clearWasm()
            },
            onCancel = {
                pendingRun.clearWasm()
            },
        )
    }

    // -----------------------------------------------------------------------
    // Component program args dialog (wasi:cli/run)
    // -----------------------------------------------------------------------
    val programArgsPending = pendingRun.programArgsExport
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
                pendingRun.clearProgramArgs()
            },
            onCancel = { pendingRun.clearProgramArgs() },
        )
    }

    // -----------------------------------------------------------------------
    // Component function args dialog
    // -----------------------------------------------------------------------
    val funcPending = pendingRun.funcExport
    if (funcPending != null) {
        FunctionArgsDialog(
            exportName = pendingRun.funcInvokeName,
            args = pendingRun.funcArgs,
            isComponent = true,
            onConfirm = { result ->
                val argsString = result.args.joinToString(", ")
                postRunExportToHost(
                    exportName = funcPending.exportName,
                    isComponent = true,
                    invokeArgs = "--invoke \"${pendingRun.funcInvokeName}($argsString)\"",
                    wasmBytes = provider.bytes.toByteArray(),
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingRun.clearComponent()
            },
            onCancel = {
                pendingRun.clearComponent()
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
        // Tab bar — hidden when there is only a single tab
        if (tabs.size > 1) {
            ScrollableTabBar(
                tabs = tabs,
                activeTab = activeTab,
                onActiveTabChange = onActiveTabChange,
            )
        }

        val currentTab = tabs.firstOrNull { it.label == activeTab }
        // Tab content
        Div({
            classes("hexana-tab-content")
            style {
                property("overflow-y", "auto")
                if (currentTab?.padding != false) {
                    property("padding", "8px 12px")
                }
                property("flex", "1")
                property("min-height", "0")
            }
        }) {
            currentTab?.content?.invoke(selection)
        }
    }
}

@Composable
private fun ScrollableTabBar(
    tabs: List<PanelTab>,
    activeTab: String,
    onActiveTabChange: (String) -> Unit,
) {
    var tabListRef by remember { mutableStateOf<HTMLElement?>(null) }
    var canScrollStart by remember { mutableStateOf(false) }
    var canScrollEnd by remember { mutableStateOf(false) }

    fun updateScrollButtons(element: HTMLElement? = tabListRef) {
        if (element == null) {
            canScrollStart = false
            canScrollEnd = false
            return
        }
        val maxScroll = (element.scrollWidth - element.clientWidth).coerceAtLeast(0)
        canScrollStart = element.scrollLeft > 1.0
        canScrollEnd = element.scrollLeft < maxScroll - 1.0
    }

    fun scrollByPage(direction: Int) {
        val element = tabListRef ?: return
        val delta = (element.clientWidth * 0.8).coerceAtLeast(80.0)
        val maxScroll = (element.scrollWidth - element.clientWidth).coerceAtLeast(0).toDouble()
        element.scrollLeft = (element.scrollLeft + direction * delta).coerceIn(0.0, maxScroll)
        updateScrollButtons(element)
    }

    val activeIndex = tabs.indexOfFirst { it.label == activeTab }
    LaunchedEffect(activeTab, tabs, tabListRef) {
        val element = tabListRef ?: return@LaunchedEffect
        if (activeIndex < 0) {
            updateScrollButtons(element)
            return@LaunchedEffect
        }
        window.requestAnimationFrame {
            val activeElement = element.children.item(activeIndex) as? HTMLElement
            if (activeElement != null) {
                val activeStart = activeElement.offsetLeft.toDouble()
                val activeEnd = activeStart + activeElement.offsetWidth
                val viewportStart = element.scrollLeft
                val viewportEnd = viewportStart + element.clientWidth

                element.scrollLeft = when {
                    activeStart < viewportStart -> activeStart
                    activeEnd > viewportEnd -> activeEnd - element.clientWidth
                    else -> element.scrollLeft
                }
            }
            updateScrollButtons(element)
        }
    }

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
        if (canScrollStart || canScrollEnd) {
            TabScrollButton(
                label = "\u2039",
                title = "Previous tabs",
                enabled = canScrollStart,
                onClick = { scrollByPage(-1) },
            )
        }

        Div({
            classes("hexana-tab-list")
            ref { element ->
                tabListRef = element
                updateScrollButtons(element)

                val scrollListener: (Event) -> Unit = {
                    updateScrollButtons(element)
                }
                val resizeListener: (Event) -> Unit = {
                    updateScrollButtons(element)
                }

                element.addEventListener("scroll", scrollListener)
                window.addEventListener("resize", resizeListener)

                val rafId = window.requestAnimationFrame {
                    updateScrollButtons(element)
                }

                onDispose {
                    tabListRef = null
                    element.removeEventListener("scroll", scrollListener)
                    window.removeEventListener("resize", resizeListener)
                    window.cancelAnimationFrame(rafId)
                }
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
                        property("white-space", "nowrap")
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

        if (canScrollStart || canScrollEnd) {
            TabScrollButton(
                label = "\u203A",
                title = "Next tabs",
                enabled = canScrollEnd,
                onClick = { scrollByPage(1) },
            )
        }
    }
}

@Composable
private fun TabScrollButton(
    label: String,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Div({
        classes("hexana-tab-scroll-button")
        if (!enabled) classes("hexana-tab-scroll-button-disabled")
        attr("title", title)
        onClick { event ->
            event.stopPropagation()
            if (enabled) onClick()
        }
    }) {
        Text(label)
    }
}

/**
 * Enriches the ZIP "Entries" tab descriptor with a row-click action that
 * posts the entry metadata to the extension host for decompression and
 * opening.
 */
private fun enrichZipEntriesTab(
    tab: TableTabDescriptor,
    entries: List<org.jetbrains.hexana.zip.ZipEntry>,
): TableTabDescriptor = TableTabDescriptor(
    name = tab.name,
    columns = tab.columns,
    rowCount = tab.rowCount,
    defaultSortColumn = tab.defaultSortColumn,
    defaultSortAscending = tab.defaultSortAscending,
    rowSelectionProvider = tab.rowSelectionProvider,
    rowAction = tab.rowAction,
    contextMenuItems = tab.contextMenuItems,
    rowClickAction = { index ->
        val entry = entries[index]
        postOpenZipEntryToHost(
            name = entry.name,
            dataOffset = entry.dataOffset,
            compressedSize = entry.compressedSize,
            uncompressedSize = entry.uncompressedSize,
            compressionMethod = entry.compressionMethod,
        )
    },
    dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
    prologueText = tab.prologueText,
    emptyMessage = tab.emptyMessage,
    childrenProvider = tab.childrenProvider,
    alwaysShowSearch = tab.alwaysShowSearch,
    searchPlaceholder = tab.searchPlaceholder,
    showIdColumn = tab.showIdColumn,
    priority = tab.priority,
)
