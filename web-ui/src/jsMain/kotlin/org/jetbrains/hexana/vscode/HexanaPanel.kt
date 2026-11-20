package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.blazor.BlazorBundle
import org.jetbrains.hexana.shared.blazor.MonoAotReport
import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.pcap.CapturedHttpRequest
import org.jetbrains.hexana.pcap.HttpReplay
import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.PlaintextHttpBody
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.SbomLocator
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.TarBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.shared.APK_CLASSES_TAB_NAME
import org.jetbrains.hexana.shared.ApkDexClasses
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.isApkFileName
import org.jetbrains.hexana.shared.RowContextMenuItemDescriptor
import org.jetbrains.hexana.shared.RowActionDescriptor
import org.jetbrains.hexana.shared.buildInvokeOption
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import org.jetbrains.hexana.shared.TabPriority
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.shared.withDexRevealInSmali
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Identifiers for non-table tabs that remain hardcoded outside the
 * [org.jetbrains.hexana.shared.TableTabsProvider] abstraction.
 */
private enum class FixedTab(val label: String) {
    Summary("Sections"),
    WatClassic("Wat"),
    Wat("WAT (virtualized)"),
    Dependencies("Dependencies"),
    Sbom("SBOM"),
}

/**
 * A unified tab entry that can be either a provider-generated table tab or a
 * fixed (hardcoded) tab.
 */
class PanelTab(
    val label: String,
    val padding: Boolean = true,
    /**
     * What selects this tab ([HexanaPanel]'s `activeTab`, a region's tab link). The label,
     * except for tabs whose shown name is shortened: a provider-script tab is labelled
     * "Header" but identified as `user:Header`, so it cannot collide with a built-in tab.
     */
    val id: String = label,
    val content: @Composable (HexSelectionState) -> Unit,
)

@Composable
fun HexanaPanel(
    provider: BinaryProvider,
    selection: HexSelectionState,
    fileName: String,
    /** Path of the open file, or blank when it has none. */
    filePath: String = "",
    heightPx: Double = 250.0,
    activeTab: String = FixedTab.Summary.label,
    onActiveTabChange: (String) -> Unit = {},
    extraTabs: List<PanelTab> = emptyList(),
    capabilities: WebUiCapabilities = WebUiCapabilities(),
    scriptTabs: List<ScriptTabState> = emptyList(),
    dialogPickedPath: String = "",
    dialogPickedContent: String = "",
    onDialogPickedPathConsumed: () -> Unit = {},
    onScriptTabConfirmed: (path: String, content: String) -> Unit = { _, _ -> },
    onScriptTabRemoved: (path: String) -> Unit = {},
    scriptExecution: ScriptExecution? = null,
    /**
     * Bumped whenever an SSL key log is installed on [provider]. Since
     * `PcapBinaryProvider.sslKeyLog` is a plain `var`, this counter is what
     * tells the tab list to rebuild so the "Packets" tab picks up decryption.
     */
    sslKeyLogGeneration: Int = 0,
    /**
     * The feature catalog paired with the project's declared runtime targets, when the host
     * supplied both. `null` leaves out the Compatibility tab and the runtime settings
     * dialog, which is how the browser webapp renders (it has no project directory).
     */
    runtimeCompatibility: WasmRuntimeCompatibility? = null,
    /** The Blazor WebAssembly app the file belongs to, read by the host; drives the Blazor App tab. */
    blazorBundle: BlazorBundle? = null,
    /** The .NET AOT breakdown of an ahead-of-time compiled runtime module, built by the host. */
    monoAot: MonoAotReport? = null,
    /** Opened by the "Configure runtimes…" action; `null` hides the action. */
    onConfigureRuntimes: (() -> Unit)? = null,
    /**
     * Latest outside request to select a row of a table tab, e.g. from "Reveal in DEX" in
     * another editor. Each new request switches to its tab and selects the row there.
     */
    tableRowSelection: TableRowSelectionRequest? = null,
    /**
     * Drags of the resize divider drawn above the panel; `null` draws none. The divider lives
     * here rather than with the caller so that it disappears in the same frame as the panel
     * when the tab list is empty (a generic binary) — reported back through an effect, it
     * lagged a frame behind and the switch to "Generic binary" blinked.
     */
    onResize: ((deltaY: Double) -> Unit)? = null,
    /**
     * Docked to the right of the hex view: the tabs are a vertical list on the panel's right
     * edge, the panel is [widthPx] wide (or nearly the whole row when [expanded]), and the
     * divider is drawn on its left and reported through [onResizeWidth].
     */
    dockedRight: Boolean = false,
    widthPx: Double = 480.0,
    /** Docked right and widened to leave only a thin strip of the hex view to drag back. */
    expanded: Boolean = false,
    /** Toggles [expanded]; `null` hides the button. */
    onToggleExpanded: (() -> Unit)? = null,
    /** Moves the panel between the bottom and the right of the hex view; `null` hides the button. */
    onToggleDock: (() -> Unit)? = null,
    /** The width a drag of the divider on the left of a right-docked panel asks for. */
    onResizeWidth: ((newWidthPx: Double) -> Unit)? = null,
) {
    val host = LocalHost.current
    val pendingRun = rememberPendingRunState()

    // Set by the Packets tab's "Replay HTTP Request…" row action.
    var pendingHttpReplay by remember(provider) { mutableStateOf<CapturedHttpRequest?>(null) }

    // "Scroll WAT to function" channel from the Problems tab into WatTabContent;
    // consumed (reset to null) by the WAT view once handled.
    val watNavFuncIdx = remember { mutableStateOf<Int?>(null) }

    // "Reveal in Smali" channel from the DEX Classes tab into the Smali tab,
    // which the caller builds; see [LocalDexSmaliReveal].
    val dexSmaliReveal = remember(provider) { mutableStateOf<Int?>(null) }

    // Outside row-selection requests, handed to the table tabs through
    // [LocalTableRowSelection]. The tab switch makes the target table compose.
    val pendingRowSelection = remember(provider) { mutableStateOf<TableRowSelectionRequest?>(null) }

    // An APK's classes, kept here rather than in the Classes tab, which composes only while
    // it is active: leaving the tab and coming back must not read every DEX file again.
    val apkClasses = remember(provider) { mutableStateOf<Result<ApkDexClasses>?>(null) }
    LaunchedEffect(tableRowSelection) {
        val request = tableRowSelection ?: return@LaunchedEffect
        pendingRowSelection.value = request
        onActiveTabChange(request.tabName)
    }

    // Lazy SBOM detection: nativeImageInfo is expensive (string scan + symbol resolution).
    // Populated by a LaunchedEffect so it never runs synchronously in the composition path.
    // Its presence is a remember key so the SBOM tab appears once detection completes.
    var sbomLocator by remember(provider) { mutableStateOf<SbomLocator?>(null) }
    var sbomGraalVersion by remember(provider) { mutableStateOf<String?>(null) }
    LaunchedEffect(provider) {
        sbomLocator = null
        sbomGraalVersion = null
        if (provider is NativeBinaryProvider) {
            val info = provider.nativeImageInfo
            sbomLocator = info?.sbom
            sbomGraalVersion = info?.version
        }
    }

    // -----------------------------------------------------------------------
    // Build the tab list: provider-generated table tabs + fixed tabs
    // -----------------------------------------------------------------------
    val tabs = remember(
        provider, fileName, extraTabs, scriptTabs.size, sbomLocator,
        capabilities.sbomVulnerabilityCheck, sslKeyLogGeneration, runtimeCompatibility, blazorBundle, monoAot,
    ) {
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
            val tarProvider = provider as? TarBinaryProvider
            data class TabEntry(val tab: PanelTab, val priority: TabPriority, val providerIndex: Int)
            val tabEntries = mutableListOf<TabEntry>()
            for ((providerIndex, p) in TableTabsProviderRegistry.providers.withIndex()) {
                val tabsContext = TableTabsContext(
                    fileName = fileName,
                    runtimeCompatibility = runtimeCompatibility,
                    blazorBundle = blazorBundle,
                    monoAot = monoAot,
                    openUrl = { url -> host.openUrl(url) },
                    openRuntimeSettings = onConfigureRuntimes,
                    problemNavigator = problemNavigator(provider, selection, onSelectTab = onActiveTabChange) { funcIdx ->
                        watNavFuncIdx.value = funcIdx
                        onActiveTabChange(FixedTab.Wat.label)
                    },
                )
                val enriched = enricher(p.tableTabs(provider, tabsContext), fileName, pendingRun)
                for (d in enriched) {
                    val desc = if (d.name == "Entries" && zipProvider != null) {
                        enrichZipEntriesTab(d, zipProvider.displayEntries, host)
                    } else if (d.name == "Entries" && tarProvider != null) {
                        enrichTarEntriesTab(d, tarProvider.displayEntries, host)
                    } else if (d.name == "Members" && provider is ArchiveBinaryProvider) {
                        enrichArchiveMembersTab(d, provider, host)
                    } else if (d.name == "Packets" && provider is PcapBinaryProvider) {
                        enrichPcapPacketsTab(d, provider, host) { pendingHttpReplay = it }
                    } else if (provider is DexBinaryProvider) {
                        d.withDexRevealInSmali { classDefIndex ->
                            dexSmaliReveal.value = classDefIndex
                            onActiveTabChange(DEX_SMALI_TAB_NAME)
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

            // Every class across the APK's DEX files, read only once the tab is opened.
            if (zipProvider != null && isApkFileName(fileName)) {
                add(PanelTab(APK_CLASSES_TAB_NAME) { sel -> ApkClassesTabContent(zipProvider, apkClasses, sel, host) })
            }

            // WAT tabs (always present for Wasm and component): the classic
            // wasmprinter text view and the virtualized view, like the
            // IntelliJ plugin's "Wat" / "WAT (virtualized)" pair.
            if (provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.WatClassic.label, padding = false) { _ ->
                    ClassicWatTabContent(provider)
                })
                add(PanelTab(FixedTab.Wat.label, padding = false) { _ ->
                    WatTabContent(provider, watNavFuncIdx)
                })
            }

            // Dependencies tab (component-model only). Double-clicking a node
            // opens embedded binaries in their own tab and selects the bytes of
            // every other entity in the hex view.
            if (provider is WasmComponentBinaryProvider) {
                add(PanelTab(FixedTab.Dependencies.label, padding = false) { sel ->
                    DependencyGraphTabContent(provider) { navigation ->
                        when (navigation) {
                            is ComponentGraphNavigation.HexRange ->
                                sel.selectRange(navigation.offset, navigation.size)
                            is ComponentGraphNavigation.NestedBinary ->
                                provider.submoduleBytes(navigation.contentOffset, navigation.contentSize)
                                    ?.let { nestedBytes ->
                                        host.openModule(
                                            navigation.index,
                                            submoduleFileName(
                                                fileName,
                                                navigation.index,
                                                navigation.contentOffset,
                                                navigation.isComponent,
                                            ),
                                            nestedBytes,
                                        )
                                    }
                        }
                    }
                })
            }

            // SBOM tab: present only when nativeImageInfo detection has completed and
            // found an embedded SBOM. sbomLocator is null until the LaunchedEffect fires.
            val locator = sbomLocator
            val graalVer = sbomGraalVersion
            if (locator != null && provider is NativeBinaryProvider) {
                add(PanelTab(FixedTab.Sbom.label) { _ ->
                    SbomTabContent(provider, locator, graalVer, fileName, capabilities.sbomVulnerabilityCheck)
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
                    // Every script tab renders from this one call site, so without a
                    // key they all share one identity: the output, the error and the
                    // "running" flag of the tab shown before would stay on screen for
                    // a tab whose own script has not produced any yet.
                    key(st.path) {
                        ScriptTabContent(
                            scriptPath = st.path,
                            scriptContent = st.content,
                            provider = provider,
                            onRequestRefresh = { host.readScriptFile(st.path) },
                            scriptExecution = scriptExecution,
                            binaryFileName = fileName,
                            binaryFilePath = filePath,
                        )
                    }
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
    val needsReset = tabs.none { it.id == activeTab } && tabs.isNotEmpty()
    if (needsReset) {
        SideEffect {
            onActiveTabChange(tabs.first().id)
        }
    }

    // -----------------------------------------------------------------------
    // Core Wasm function args dialog
    // -----------------------------------------------------------------------
    val wasmPending = pendingRun.wasmExport
    if (wasmPending != null) {
        // Descriptors must keep a stable identity while the dialog is open:
        // rebuilding them on every recomposition resets the dialog's
        // remember(args) field state and wipes typed input.
        val wasmArgs = remember(wasmPending) { wasmArgDescriptors(pendingRun.wasmParams) }
        FunctionArgsDialog(
            exportName = wasmPending.name,
            args = wasmArgs,
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
                val argsString = result.args.mapIndexed { i, value ->
                    pendingRun.funcArgs.getOrNull(i)?.format?.invoke(value) ?: value
                }.joinToString(", ")
                host.runExport(
                    exportName = funcPending.exportName,
                    invokeArgs = buildInvokeOption("${pendingRun.funcInvokeName}($argsString)"),
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

    pendingHttpReplay?.let { captured ->
        HttpReplayDialog(captured, onClose = { pendingHttpReplay = null })
    }

    // -----------------------------------------------------------------------
    // Panel layout
    // -----------------------------------------------------------------------
    val tabsEmpty = tabs.isEmpty()
    val showTabList = tabs.size > 1 || scriptTabs.isNotEmpty()
    val closeScriptTab: (String) -> Unit = { label ->
        val st = scriptTabs.find {
            it.path.substringAfterLast('/').substringAfterLast('\\') == label
        }
        if (st != null) {
            onScriptTabRemoved(st.path)
            host.scriptTabRemoved(st.path)
        }
    }
    var panelRef by remember { mutableStateOf<HTMLElement?>(null) }
    // A drag is measured once, at mouse-down, and then follows the pointer from there. Reading the
    // width on every move fed back a value the previous move's width had not reached yet, so a drag
    // that ended where it started left the panel wider. Measuring rather than using [widthPx] makes a
    // drag from the expanded state, or from a width clamped by a narrow window, start where it is.
    val drag = remember { DividerDrag() }
    if (dockedRight) {
        if (!tabsEmpty && onResizeWidth != null) {
            ColumnResizeDivider(
                onDragStart = {
                    drag.startWidth = panelRef?.offsetWidth?.toDouble() ?: widthPx
                    drag.totalDeltaX = 0.0
                },
            ) { deltaX ->
                drag.totalDeltaX += deltaX
                // The panel is right of the divider: dragging right narrows it.
                onResizeWidth(drag.startWidth - drag.totalDeltaX)
            }
        }
    } else if (!tabsEmpty && onResize != null) {
        ResizeDivider(onResize)
    }
    // Hidden rather than left out, so the run and replay dialogs composed above keep working.
    Div({
        classes("hexana-panel")
        if (dockedRight) classes("hexana-panel-docked-right")
        style {
            if (dockedRight) {
                property("border-left", "1px solid var(--hexana-border, #333)")
                // The width includes the border, so the offsetWidth a divider drag starts from is the
                // width stored back: with content-box sizing every drag event added the border's pixel.
                property("box-sizing", "border-box")
                property("flex", "0 0 auto")
                // Expanded leaves a strip of the hex view wide enough to grab the divider.
                property("width", if (expanded) "calc(100% - ${COLLAPSED_HEX_WIDTH_PX}px)" else "${widthPx}px")
                property("max-width", "calc(100% - ${COLLAPSED_HEX_WIDTH_PX}px)")
                property("min-width", "${MIN_DOCKED_PANEL_WIDTH_PX}px")
            } else {
                property("border-top", "1px solid var(--hexana-border, #333)")
                property("flex", "0 0 auto")
                height(heightPx.px)
                property("min-height", "80px")
            }
            // One declaration: a later `display` in the same block would override `none`.
            display(if (tabsEmpty) DisplayStyle.None else DisplayStyle.Flex)
            property("flex-direction", "column")
            property("overflow", "hidden")
        }
        ref { element ->
            panelRef = element
            onDispose { panelRef = null }
        }
    }) {
        if (dockedRight) {
            // Header: expand on the left, dock and "+" on the right; the tabs are listed below it.
            DockedPanelHeader(
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                onToggleDock = onToggleDock,
                onAddTab = { showAddScriptDialog = true },
            )
        } else if (showTabList) {
            // Tab bar — hidden when there is only a single tab
            ScrollableTabBar(
                tabs = tabs,
                activeTab = activeTab,
                onActiveTabChange = onActiveTabChange,
                closableTabs = scriptTabLabels,
                onCloseTab = closeScriptTab,
                onAddTab = { showAddScriptDialog = true },
                onToggleDock = onToggleDock,
            )
        }

        if (showAddScriptDialog) {
            AddScriptTabDialog(
                scriptPath = dialogPickedPath,
                existingScriptPaths = scriptTabs.map { it.path }.toSet(),
                onBrowse = {
                    host.pickScriptFile()
                },
                onCreateFromTemplate = { fileName, template ->
                    host.createScriptFromTemplate(fileName, template, ScriptApiReference.jsConfig())
                },
                onOpenDts = { raw ->
                    host.openDts(raw)
                },
                showExamples = capabilities.bundledScriptExamples,
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

        val tabContent: @Composable () -> Unit = {
            val currentTab = tabs.firstOrNull { it.id == activeTab }
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
                    property("flex", "1 1 0")
                    property("min-height", "0")
                    // Lets the content scroll sideways instead of widening a narrow docked panel.
                    property("min-width", "0")
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
                CompositionLocalProvider(
                    LocalDexSmaliReveal provides dexSmaliReveal,
                    LocalTableRowSelection provides pendingRowSelection,
                ) {
                    currentTab?.content?.invoke(selection)
                }
            }
        }
        // The same wrapper in both docks, so the open tab's elements stay where they are when the
        // panel is re-docked: re-parented, they would be recreated or lose their scroll position.
        Div({
            style {
                display(DisplayStyle.Flex)
                property("flex-direction", "row")
                property("flex", "1")
                property("min-height", "0")
            }
        }) {
            tabContent()
            if (dockedRight && showTabList) {
                VerticalTabList(
                    tabs = tabs,
                    activeTab = activeTab,
                    onActiveTabChange = onActiveTabChange,
                    closableTabs = scriptTabLabels,
                    onCloseTab = closeScriptTab,
                )
            }
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
    /** Moves the tabs beside the hex view; `null` hides the button. */
    onToggleDock: (() -> Unit)? = null,
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

    val activeIndex = tabs.indexOfFirst { it.id == activeTab }
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
                val isActive = tab.id == activeTab
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
                        onActiveTabChange(tab.id)
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

        if (onToggleDock != null) {
            PanelIconButton(
                iconClass = "hexana-icon-dock-right",
                title = "Move tabs to the right of the hex view",
                onClick = onToggleDock,
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

/** The divider drag in progress; plain fields, since nothing renders them. */
private class DividerDrag(var startWidth: Double = 0.0, var totalDeltaX: Double = 0.0)

/** Width of the hex view strip a right-docked, expanded panel leaves to grab the divider by. */
private const val COLLAPSED_HEX_WIDTH_PX = 12

/** Narrowest a right-docked panel can be dragged to. */
private const val MIN_DOCKED_PANEL_WIDTH_PX = 160

/** Widest the vertical tab list grows before titles are ellipsized. */
private const val VERTICAL_TAB_LIST_MAX_WIDTH_PX = 220

/** The header of a right-docked panel, which has no horizontal tab bar to carry its buttons. */
@Composable
private fun DockedPanelHeader(
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
    onToggleDock: (() -> Unit)?,
    onAddTab: () -> Unit,
) {
    Div({
        classes("hexana-tab-bar", "hexana-docked-panel-header")
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("flex-shrink", "0")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("background", "var(--hexana-tab-bg, rgba(255,255,255,0.03))")
            property("min-height", "28px")
        }
    }) {
        if (onToggleExpanded != null) {
            PanelIconButton(
                iconClass = if (expanded) "hexana-icon-collapse" else "hexana-icon-expand",
                title = if (expanded) "Restore the hex view" else "Expand the tabs over the hex view",
                onClick = onToggleExpanded,
            )
        }
        Div({ style { property("flex", "1") } })
        if (onToggleDock != null) {
            PanelIconButton(
                iconClass = "hexana-icon-dock-bottom",
                title = "Move tabs below the hex view",
                onClick = onToggleDock,
            )
        }
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

/**
 * The tabs of a right-docked panel, one per row on the panel's right edge: as wide as the
 * longest title up to [VERTICAL_TAB_LIST_MAX_WIDTH_PX] (longer ones are ellipsized), scrolling
 * when they do not all fit. The selected row is kept in view, since selection also changes
 * without a click (an added script tab, a section label, the size badge).
 */
@Composable
private fun VerticalTabList(
    tabs: List<PanelTab>,
    activeTab: String,
    onActiveTabChange: (String) -> Unit,
    closableTabs: Set<String>,
    onCloseTab: (String) -> Unit,
) {
    val host = LocalHost.current
    var listRef by remember { mutableStateOf<HTMLElement?>(null) }
    val activeIndex = tabs.indexOfFirst { it.id == activeTab }
    LaunchedEffect(activeIndex, tabs.size, listRef) {
        val list = listRef ?: return@LaunchedEffect
        if (activeIndex < 0) return@LaunchedEffect
        window.requestAnimationFrame {
            (list.children.item(activeIndex) as? HTMLElement)?.asDynamic()?.scrollIntoView(js("({block: 'nearest'})"))
        }
    }
    Div({
        classes("hexana-vertical-tab-list")
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("flex", "0 0 auto")
            property("max-width", "${VERTICAL_TAB_LIST_MAX_WIDTH_PX}px")
            property("overflow-y", "auto")
            property("overflow-x", "hidden")
            property("border-left", "1px solid var(--hexana-border, #333)")
            property("background", "var(--hexana-tab-bg, rgba(255,255,255,0.03))")
        }
        ref { element ->
            listRef = element
            onDispose { listRef = null }
        }
    }) {
        for (tab in tabs) {
            val isActive = tab.id == activeTab
            val isClosable = tab.label in closableTabs
            Div({
                classes("hexana-tab", "hexana-vertical-tab")
                if (isActive) classes("hexana-tab-active")
                attr("title", tab.label)
                style {
                    display(DisplayStyle.Flex)
                    property("align-items", "center")
                    property("gap", "4px")
                    property("padding", "5px 10px 5px 12px")
                    property("cursor", "pointer")
                    property("font-size", "12px")
                    property("user-select", "none")
                    property("white-space", "nowrap")
                    // The selection marker sits on the outer edge, like a horizontal tab's underline.
                    property("border-right", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                    property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                    property("background", if (isActive) "var(--hexana-tab-active-bg, rgba(255,255,255,0.06))" else "transparent")
                }
                onClick {
                    onActiveTabChange(tab.id)
                    host.tabSelected(tab.label)
                }
            }) {
                Span({
                    style {
                        property("flex", "1 1 auto")
                        property("min-width", "0")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                    }
                }) { Text(tab.label) }
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
}

/** A small icon button for panel headers; [iconClass] is an SVG mask defined in `editor.css`. */
@Composable
private fun PanelIconButton(iconClass: String, title: String, onClick: () -> Unit) {
    Div({
        classes("hexana-panel-icon-button")
        attr("title", title)
        attr("aria-label", title)
        attr("role", "button")
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Span({ classes("hexana-panel-icon", iconClass) })
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
    val classifications = members.map { provider.classifyMember(it) }
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
                val baseName = member.name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { member.name }
                val bytes = provider.memberBytes(member)?.toByteArray()
                if (bytes != null) {
                    host.openModule(member.index, baseName, bytes)
                } else {
                    // A member of a large archive the webview holds only part of: stored raw,
                    // so the extension reads it from disk like a tar entry.
                    host.openTarEntry(baseName, member.contentRange.start, member.contentRange.length)
                }
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
 * Enriches the TAR "Entries" tab descriptor with a row-click action that
 * posts the entry metadata to the extension host for extraction and opening.
 */
private fun enrichTarEntriesTab(
    tab: TableTabDescriptor,
    entries: List<org.jetbrains.hexana.tar.TarEntry>,
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
        host.openTarEntry(
            name = entry.name,
            dataOffset = entry.dataOffset,
            size = entry.size,
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
    host: Host,
    onReplay: (CapturedHttpRequest) -> Unit,
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

    // Decrypted plaintext was never in the file, so the file-offset path above
    // cannot reach an HTTPS body. The shared tab hands the record's plaintext
    // over through its row detail hook - the same bytes the "Decrypted TLS"
    // panel shows - and the body is split out of that instead.
    fun decryptedHttpBody(index: Int): PlaintextHttpBody? {
        val plaintext = tab.rowDetailProvider?.invoke(index)?.bytes ?: return null
        return PcapDissector.httpBodyInPlaintext(plaintext)
    }

    // isVisible runs on every menu build, so the answer is cached; the body
    // itself is not, to keep decrypted copies of rows nobody opens off the heap.
    val hasDecryptedBodyCache = HashMap<Int, Boolean>()
    fun hasDecryptedHttpBody(index: Int): Boolean =
        hasDecryptedBodyCache.getOrPut(index) { decryptedHttpBody(index) != null }

    fun hasCleartextHttpBody(d: PcapDissectedPacket): Boolean =
        d.httpBodyFileOffset >= 0 && d.httpBodySize > 0

    val openBodyItem = RowContextMenuItemDescriptor(
        labelProvider = { "Open HTTP Body" },
        action = { index ->
            val d = dissected(index)
            val fromFile = hasCleartextHttpBody(d)
            if (!fromFile && !hasDecryptedHttpBody(index)) return@RowContextMenuItemDescriptor
            try {
                val body = if (fromFile) {
                    val raw = PcapDissector.reassembleHttpBody(
                        provider.bytes, packets, index, linkType, d,
                    ) ?: return@RowContextMenuItemDescriptor
                    PlaintextHttpBody(raw, d.httpContentType, d.httpContentEncoding)
                } else {
                    decryptedHttpBody(index) ?: return@RowContextMenuItemDescriptor
                }
                val ext = httpContentTypeToExtension(body.contentType)
                val fileName = "http-body-packet-${index + 1}$ext"
                host.openHttpBody(fileName, body.bytes, body.contentType, body.contentEncoding)
            } catch (_: Exception) {
                // Extraction failed — silently ignore
            }
        },
        isVisible = { index ->
            hasCleartextHttpBody(dissected(index)) || hasDecryptedHttpBody(index)
        },
    )

    fun decryptedPlaintext(index: Int): ByteArray? = tab.rowDetailProvider?.invoke(index)?.bytes

    // isVisible runs on every menu build; the request itself is rebuilt on demand.
    val isReplayableCache = HashMap<Int, Boolean>()
    fun isReplayable(index: Int): Boolean = isReplayableCache.getOrPut(index) {
        try {
            HttpReplay.mayHoldRequest(dissected(index)) { decryptedPlaintext(index) }
        } catch (_: Exception) {
            false
        }
    }

    fun replayItem(label: String, open: (CapturedHttpRequest) -> Unit) = RowContextMenuItemDescriptor(
        labelProvider = { label },
        action = { index ->
            try {
                HttpReplay.capturedRequest(provider.bytes, packets, index, linkType) { decryptedPlaintext(index) }
                    ?.let(open)
            } catch (_: Exception) {
                // The row did not hold a request after all; the menu said it might.
            }
        },
        isVisible = { index -> isReplayable(index) },
    )

    val replayItems = if (host.canReplayHttp) {
        listOf(
            replayItem("Replay HTTP Request…", onReplay),
            replayItem("Open Request as .http File") { host.openCapturedRequestFile(it) },
        )
    } else {
        emptyList()
    }

    // The gutter's run icon opens the replay dialog on rows that hold a
    // request; a null tooltip hides it on the rest.
    val replayAction = if (host.canReplayHttp) {
        RowActionDescriptor(
            label = "Replay HTTP Request",
            tooltipProvider = { index -> if (isReplayable(index)) "Replay HTTP request…" else null },
            action = { index ->
                try {
                    HttpReplay.capturedRequest(provider.bytes, packets, index, linkType) { decryptedPlaintext(index) }
                        ?.let(onReplay)
                } catch (_: Exception) {
                    // The row did not hold a request after all.
                }
            },
        )
    } else {
        tab.rowAction
    }

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = replayAction,
        contextMenuItems = tab.contextMenuItems + openBodyItem + replayItems,
        rowClickAction = tab.rowClickAction,
        rowDoubleClickAction = tab.rowDoubleClickAction,
        dynamicContextMenuProvider = tab.dynamicContextMenuProvider,
        prologueText = tab.prologueText,
        emptyMessage = tab.emptyMessage,
        childrenProvider = tab.childrenProvider,
        alwaysShowSearch = tab.alwaysShowSearch,
        searchPlaceholder = tab.searchPlaceholder,
        showIdColumn = tab.showIdColumn,
        priority = tab.priority,
        predefinedFilters = tab.predefinedFilters,
        defaultFilterShowAll = tab.defaultFilterShowAll,
        // Rebuilt field by field, so anything the shared tab set has to be
        // carried over by hand - the decrypted-plaintext panel included.
        rowDetailProvider = tab.rowDetailProvider,
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
