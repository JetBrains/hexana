package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.shared.RowContextMenuItemDescriptor
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
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
    Dependencies("Dependencies"),
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
    scriptTabs: List<ScriptTabState> = emptyList(),
    dialogPickedPath: String = "",
    dialogPickedContent: String = "",
    onDialogPickedPathConsumed: () -> Unit = {},
    onScriptTabConfirmed: (path: String, content: String) -> Unit = { _, _ -> },
    onScriptTabRemoved: (path: String) -> Unit = {},
) {
    val host = LocalHost.current
    val pendingRun = rememberPendingRunState()

    // "Scroll WAT to function" channel from the Problems tab into WatTabContent;
    // consumed (reset to null) by the WAT view once handled.
    val watNavFuncIdx = remember { mutableStateOf<Int?>(null) }

    // -----------------------------------------------------------------------
    // Build the tab list: provider-generated table tabs + fixed tabs
    // -----------------------------------------------------------------------
    val tabs = remember(provider, fileName, extraTabs, scriptTabs.size) {
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
            val enricher = provider.getTabsEnricher(host)
            val zipProvider = provider as? ZipBinaryProvider
            data class TabEntry(val tab: PanelTab, val priority: TabPriority, val providerIndex: Int)
            val tabEntries = mutableListOf<TabEntry>()
            for ((providerIndex, p) in TableTabsProviderRegistry.providers.withIndex()) {
                val enriched = enricher(p.tableTabs(provider, TableTabsContext(fileName = fileName)), fileName, pendingRun)
                for (d in enriched) {
                    val desc = if (d.name == "Entries" && zipProvider != null) {
                        enrichZipEntriesTab(d, zipProvider.displayEntries, host)
                    } else if (d.name == "Members" && provider is ArchiveBinaryProvider) {
                        enrichArchiveMembersTab(d, provider, host)
                    } else if (d.name == "Packets" && provider is PcapBinaryProvider) {
                        enrichPcapPacketsTab(d, provider)
                    } else if (d.name == "Problems" && provider is WasmBinaryProvider) {
                        enrichProblemsTab(d, provider, selection) { funcIdx ->
                            watNavFuncIdx.value = funcIdx
                            onActiveTabChange(FixedTab.Wat.label)
                        }
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
                    WatTabContent(provider, watNavFuncIdx)
                })
            }

            // Dependencies tab (component-model only)
            if (provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.Dependencies.label, padding = false) { _ ->
                    DependencyGraphTabContent(provider)
                })
            }

            // Extra tabs injected by the caller (e.g. format-specific tabs
            // for class files, DEX files, ZIP archives).
            addAll(extraTabs)

            // Low-priority tabs go after all other panels (including non-table tabs).
            addAll(lowEntries.map { it.tab })

            // Script tabs added by the user
            for (st in scriptTabs) {
                val label = st.path.substringAfterLast('/').substringAfterLast('\\')
                add(PanelTab(label, padding = false) { _ ->
                    ScriptTabContent(
                        scriptPath = st.path,
                        scriptContent = st.content,
                        provider = provider,
                        onRequestRefresh = { host.readScriptFile(st.path) },
                    )
                })
            }
        }
    }

    // Labels of script tabs for close button rendering
    val scriptTabLabels = remember(scriptTabs.size) {
        scriptTabs.map { it.path.substringAfterLast('/').substringAfterLast('\\') }.toSet()
    }

    // State for the "Add Script Tab" dialog
    var showAddScriptDialog by remember { mutableStateOf(false) }

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
                host.runExport(
                    exportName = wasmPending.name,
                    invokeArgs = "--invoke ${wasmPending.name}",
                    programArgs = programArgs,
                    isComponent = false,
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
                host.runExport(
                    exportName = programArgsPending.exportName,
                    invokeArgs = "",
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
                host.runExport(
                    exportName = funcPending.exportName,
                    invokeArgs = "--invoke \"${pendingRun.funcInvokeName}($argsString)\"",
                    isComponent = true,
                    programArgs = "",
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
        if (tabs.size > 1 || scriptTabs.isNotEmpty()) {
            ScrollableTabBar(
                tabs = tabs,
                activeTab = activeTab,
                onActiveTabChange = onActiveTabChange,
                closableTabs = scriptTabLabels,
                onCloseTab = { label ->
                    val st = scriptTabs.find {
                        it.path.substringAfterLast('/').substringAfterLast('\\') == label
                    }
                    if (st != null) {
                        onScriptTabRemoved(st.path)
                        host.scriptTabRemoved(st.path)
                    }
                },
                onAddTab = { showAddScriptDialog = true },
            )
        }

        if (showAddScriptDialog) {
            AddScriptTabDialog(
                scriptPath = dialogPickedPath,
                existingScriptPaths = scriptTabs.map { it.path }.toSet(),
                onBrowse = {
                    host.pickScriptFile()
                },
                onOpenDts = {
                    host.openDts()
                },
                onConfirm = { path ->
                    showAddScriptDialog = false
                    onScriptTabConfirmed(path, dialogPickedContent)
                    onDialogPickedPathConsumed()
                    // Switch to the newly added script tab
                    val newLabel = path.substringAfterLast('/').substringAfterLast('\\')
                    onActiveTabChange(newLabel)
                },
                onCancel = {
                    showAddScriptDialog = false
                    onDialogPickedPathConsumed()
                },
            )
        }

        val currentTab = tabs.firstOrNull { it.label == activeTab }
        var tabContentRef by remember { mutableStateOf<HTMLElement?>(null) }

        // Focus the tab content whenever the active tab changes
        LaunchedEffect(activeTab) {
            // Double rAF to ensure the new tab content is rendered before focusing
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                window.requestAnimationFrame {
                    window.requestAnimationFrame {
                        tabContentRef?.focus()
                        cont.resumeWith(Result.success(Unit))
                    }
                }
            }
        }

        // Tab content
        Div({
            classes("hexana-tab-content")
            tabIndex(-1)
            style {
                property("overflow-y", "auto")
                if (currentTab?.padding != false) {
                    property("padding", "8px 12px")
                }
                property("flex", "1")
                property("min-height", "0")
                property("outline", "none")
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
            }
            onClick { e ->
                val target = e.target as? HTMLElement ?: return@onClick
                // Focus the tab content when clicking on empty area (not on interactive children)
                val tag = target.tagName.uppercase()
                if (tag != "INPUT" && tag != "BUTTON" && tag != "A" && tag != "SELECT" && tag != "TEXTAREA") {
                    tabContentRef?.focus()
                }
            }
            ref { element ->
                tabContentRef = element
                onDispose { tabContentRef = null }
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
    closableTabs: Set<String> = emptySet(),
    onCloseTab: (String) -> Unit = {},
    onAddTab: () -> Unit = {},
) {
    val host = LocalHost.current
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
                val isClosable = tab.label in closableTabs
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
                        if (isClosable) {
                            display(DisplayStyle.Flex)
                            property("align-items", "center")
                            property("gap", "4px")
                        }
                    }
                    onClick {
                        onActiveTabChange(tab.label)
                        host.tabSelected(tab.label)
                    }
                }) {
                    Text(tab.label)
                    if (isClosable) {
                        Span({
                            classes("hexana-tab-close")
                            attr("title", "Close tab")
                            style {
                                property("font-size", "10px")
                                property("opacity", "0.6")
                                property("cursor", "pointer")
                                property("padding", "0 2px")
                            }
                            onClick { event ->
                                event.stopPropagation()
                                onCloseTab(tab.label)
                            }
                        }) {
                            Text("\u2715")
                        }
                    }
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

        // "+" button to add a script tab
        Div({
            classes("hexana-tab-add")
            attr("title", "Add custom script tab")
            style {
                property("padding", "4px 8px")
                property("cursor", "pointer")
                property("font-size", "14px")
                property("user-select", "none")
                property("color", "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                property("flex-shrink", "0")
            }
            onClick { onAddTab() }
        }) {
            Text("+")
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
 * Enriches the archive "Members" tab descriptor with a row-click action that
 * extracts the clicked member's bytes and posts them to the extension host
 * for opening in a new editor tab via [postOpenModuleToHost].
 */
private fun enrichArchiveMembersTab(
    tab: TableTabDescriptor,
    provider: ArchiveBinaryProvider,
    host: Host,
): TableTabDescriptor {
    val members = provider.members.filterNot { it.isBookkeeping }
    val classifications = members.map {
        org.jetbrains.hexana.providers.classifyArchiveMember(provider.bytes, it.contentRange)
    }
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = tab.rowAction,
        contextMenuItems = tab.contextMenuItems,
        rowClickAction = { index ->
            val member = members[index]
            val cls = classifications[index]
            if (cls.openable) {
                val bytes = provider.bytes
                    .slice(member.contentRange.offset, member.contentRange.size)
                    .toByteArray()
                val baseName = member.name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { member.name }
                host.openModule(member.index, baseName, bytes)
            }
        },
        dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
        prologueText = tab.prologueText,
        emptyMessage = tab.emptyMessage,
        childrenProvider = tab.childrenProvider,
        alwaysShowSearch = tab.alwaysShowSearch,
        searchPlaceholder = tab.searchPlaceholder,
        showIdColumn = tab.showIdColumn,
        priority = tab.priority,
        predefinedFilters = tab.predefinedFilters,
    )
}

/**
 * Enriches the ZIP "Entries" tab descriptor with a row-click action that
 * posts the entry metadata to the extension host for decompression and
 * opening.
 */
private fun enrichZipEntriesTab(
    tab: TableTabDescriptor,
    entries: List<org.jetbrains.hexana.zip.ZipEntry>,
    host: Host,
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
        host.openZipEntry(
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
    predefinedFilters = tab.predefinedFilters,
)

/**
 * Enriches the PCAP "Packets" tab with a context-menu action that extracts
 * the HTTP response body from a packet and opens it in a new editor tab.
 */
private fun enrichPcapPacketsTab(
    tab: TableTabDescriptor,
    provider: PcapBinaryProvider,
): TableTabDescriptor {
    val pcap = provider.parsedPcapFile ?: return tab
    val packets = pcap.packets
    val linkType = pcap.header.network

    val dissectedCache = HashMap<Int, PcapDissectedPacket>()
    fun dissected(index: Int): PcapDissectedPacket = dissectedCache.getOrPut(index) {
        try {
            PcapDissector.dissect(provider.bytes, packets[index], linkType)
        } catch (_: Exception) {
            PcapDissectedPacket("", "", "?", "")
        }
    }

    val openBodyItem = RowContextMenuItemDescriptor(
        labelProvider = { "Open HTTP Body" },
        action = { index ->
            val d = dissected(index)
            if (d.httpBodyFileOffset < 0 || d.httpBodySize <= 0) return@RowContextMenuItemDescriptor
            try {
                val bodyBytes = provider.bytes
                    .slice(d.httpBodyFileOffset.toInt(), d.httpBodySize)
                    .toByteArray()
                val ext = httpContentTypeToExtension(d.httpContentType)
                val fileName = "http-body-packet-${index + 1}$ext"
                postOpenHttpBodyToHost(fileName, bodyBytes, d.httpContentType)
            } catch (_: Exception) {
                // Extraction failed — silently ignore
            }
        },
        isVisible = { index ->
            val d = dissected(index)
            d.httpBodyFileOffset >= 0 && d.httpBodySize > 0
        },
    )

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = tab.rowAction,
        contextMenuItems = tab.contextMenuItems + openBodyItem,
        rowClickAction = tab.rowClickAction,
        dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
        prologueText = tab.prologueText,
        emptyMessage = tab.emptyMessage,
        childrenProvider = tab.childrenProvider,
        alwaysShowSearch = tab.alwaysShowSearch,
        searchPlaceholder = tab.searchPlaceholder,
        showIdColumn = tab.showIdColumn,
        priority = tab.priority,
        predefinedFilters = tab.predefinedFilters,
    )
}

/** Maps an HTTP Content-Type to a file extension hint. */
private fun httpContentTypeToExtension(contentType: String?): String {
    if (contentType == null) return ""
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when {
        mime == "application/json" -> ".json"
        mime == "application/xml" || mime == "text/xml" -> ".xml"
        mime == "text/html" -> ".html"
        mime == "text/css" -> ".css"
        mime == "text/javascript" || mime == "application/javascript" -> ".js"
        mime == "text/plain" -> ".txt"
        mime == "application/octet-stream" -> ".bin"
        mime == "application/wasm" -> ".wasm"
        mime == "application/zip" -> ".zip"
        mime == "image/png" -> ".png"
        mime == "image/jpeg" -> ".jpg"
        mime == "image/gif" -> ".gif"
        mime == "image/svg+xml" -> ".svg"
        mime.startsWith("text/") -> ".txt"
        else -> ""
    }
}
