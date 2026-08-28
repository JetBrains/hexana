package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.PredefinedFilter
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.vscode.BinaryEditorContent
import org.jetbrains.hexana.vscode.ComponentDependencyGraphEdgeData
import org.jetbrains.hexana.vscode.ComponentDependencyGraphNodeData
import org.jetbrains.hexana.vscode.ComponentGraphNavigation
import org.jetbrains.hexana.vscode.DependencyGraphTabContent
import org.jetbrains.hexana.vscode.HexSelectionState
import org.jetbrains.hexana.vscode.LocalHost
import org.jetbrains.hexana.vscode.PanelTab
import org.jetbrains.hexana.vscode.PanelTableRow
import org.jetbrains.hexana.vscode.SearchableTable
import org.jetbrains.hexana.vscode.ScriptTabState
import org.jetbrains.hexana.vscode.submoduleFileName
import org.jetbrains.hexana.vscode.TableTabContent
import org.jetbrains.hexana.vscode.WebUiCapabilities
import org.jetbrains.hexana.vscode.WorkerNativeImageToolbarPresentation
import org.jetbrains.hexana.vscode.WorkerWasmToolbarPresentation
import org.jetbrains.hexana.vscode.scrollHexViewerTo

@Composable
internal fun WorkerWasmEditorContent(
    fileName: String,
    parentFileName: String?,
    bytes: ByteArray,
    response: BoundedParserWorkerSuccess,
    capabilities: WebUiCapabilities,
    onLegacyWasmViewRequested: (String) -> Unit,
    onLegacyWasmReparseRequested: (ByteArray) -> Unit,
    scriptTabs: List<ScriptTabState> = emptyList(),
    dialogPickedPath: String = "",
    dialogPickedContent: String = "",
    onDialogPickedPathConsumed: () -> Unit = {},
    onScriptTabConfirmed: (path: String, content: String) -> Unit = { _, _ -> },
    onScriptTabRemoved: (path: String) -> Unit = {},
) {
    val presentation = response.presentation
    if (presentation.format != "core_wasm" && presentation.format != "wasm_component") {
        WorkerBinaryEditorContent(
            fileName = fileName,
            parentFileName = parentFileName,
            bytes = bytes,
            response = response,
            capabilities = capabilities,
            onLegacyReparseRequested = onLegacyWasmReparseRequested,
            scriptTabs = scriptTabs,
            dialogPickedPath = dialogPickedPath,
            dialogPickedContent = dialogPickedContent,
            onDialogPickedPathConsumed = onDialogPickedPathConsumed,
            onScriptTabConfirmed = onScriptTabConfirmed,
            onScriptTabRemoved = onScriptTabRemoved,
        )
        return
    }
    val provider = remember(bytes) { GenericBinaryProvider(bytes.toCommonByteBuffer()) }
    val host = LocalHost.current
    val scriptExecution = remember(bytes, fileName) { WorkerScriptExecution(bytes, fileName) }
    val ranges = remember(presentation.ranges) {
        presentation.ranges.map { range ->
            StructureRange(range.offset.toLong(), range.size.toLong(), range.label)
        }
    }
    val toolbarPresentation = remember(presentation.format, presentation.toolbar) {
        WorkerWasmToolbarPresentation(
            isComponent = presentation.format == "wasm_component",
            runnableExportName = presentation.toolbar.runnableExportName,
            proposalIds = presentation.toolbar.proposalIds,
        )
    }
    val extraTabs = buildList {
        add(PanelTab("Sections") { selection ->
            WorkerWasmPartialPanel(response.partial) {
                WorkerWasmSummaryTab(presentation, selection)
            }
        })
        presentation.tables.forEach { table ->
            add(PanelTab(table.id) { selection ->
                WorkerWasmPartialPanel(response.partial) {
                    WorkerWasmTableTab(table, selection)
                }
            })
        }
        presentation.componentGraph?.let { graph ->
            add(PanelTab("Dependencies", padding = false) { selection ->
                WorkerWasmPartialPanel(response.partial) {
                    DependencyGraphTabContent(
                        nodes = graph.nodes.map { node ->
                            ComponentDependencyGraphNodeData(
                                node.id, node.kind, node.label, node.sublabel, node.offset, node.size,
                            )
                        },
                        edges = graph.edges.map { edge ->
                            ComponentDependencyGraphEdgeData(edge.fromId, edge.toId, edge.label)
                        },
                        // Double-click opens embedded binaries in their own tab and
                        // selects the bytes of every other entity in the hex view.
                        onNavigate = { navigation ->
                            when (navigation) {
                                is ComponentGraphNavigation.HexRange ->
                                    selection.selectRange(navigation.offset, navigation.size)
                                is ComponentGraphNavigation.NestedBinary -> {
                                    // The Worker validated the range against the input length;
                                    // re-check it here because the slice comes off the Window copy.
                                    val end = navigation.contentOffset + navigation.contentSize
                                    if (navigation.contentOffset >= 0 && end <= bytes.size) {
                                        host.openModule(
                                            navigation.index,
                                            submoduleFileName(
                                                fileName,
                                                navigation.index,
                                                navigation.contentOffset,
                                                navigation.isComponent,
                                            ),
                                            bytes.copyOfRange(navigation.contentOffset, end),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            })
        }
        presentation.legacyTabs.forEach { label ->
            add(PanelTab(label, padding = false) { _ ->
                LegacyWasmTab(label, onLegacyWasmViewRequested)
            })
        }
    }

    BinaryEditorContent(
        fileName = fileName,
        parentFileName = parentFileName,
        provider = provider,
        extraTabs = extraTabs,
        capabilities = capabilities,
        scriptTabs = scriptTabs,
        dialogPickedPath = dialogPickedPath,
        dialogPickedContent = dialogPickedContent,
        onDialogPickedPathConsumed = onDialogPickedPathConsumed,
        onScriptTabConfirmed = onScriptTabConfirmed,
        onScriptTabRemoved = onScriptTabRemoved,
        initialStructureRanges = ranges,
        fileTypeLabelOverride = presentation.displayLabel,
        onLegacyReparseRequested = onLegacyWasmReparseRequested,
        workerWasmToolbarPresentation = toolbarPresentation,
        scriptExecution = scriptExecution,
    )
}

@Composable
private fun WorkerBinaryEditorContent(
    fileName: String,
    parentFileName: String?,
    bytes: ByteArray,
    response: BoundedParserWorkerSuccess,
    capabilities: WebUiCapabilities,
    onLegacyReparseRequested: (ByteArray) -> Unit,
    scriptTabs: List<ScriptTabState>,
    dialogPickedPath: String,
    dialogPickedContent: String,
    onDialogPickedPathConsumed: () -> Unit,
    onScriptTabConfirmed: (path: String, content: String) -> Unit,
    onScriptTabRemoved: (path: String) -> Unit,
) {
    val presentation = response.presentation
    val provider = remember(bytes) { GenericBinaryProvider(bytes.toCommonByteBuffer()) }
    val scriptExecution = remember(bytes, fileName) { WorkerScriptExecution(bytes, fileName) }
    val ranges = remember(presentation.ranges) {
        presentation.ranges.map { StructureRange(it.offset.toLong(), it.size.toLong(), it.label) }
    }
    val nativeImageToolbar = remember(presentation.toolbar.nativeImage) {
        presentation.toolbar.nativeImage?.let { nativeImage ->
            WorkerNativeImageToolbarPresentation(
                version = nativeImage.version,
                edition = nativeImage.edition,
                platform = nativeImage.platform,
                hasSbom = nativeImage.hasSbom,
            )
        }
    }
    val sizeTooltip = remember(presentation.tables) { presentation.workerSizeTooltip() }
    val tabs = presentation.tables.map { table ->
        PanelTab(table.id) { selection ->
            WorkerWasmPartialPanel(response.partial) { WorkerWasmTableTab(table, selection) }
        }
    }
    BinaryEditorContent(
        fileName = fileName,
        parentFileName = parentFileName,
        provider = provider,
        extraTabs = tabs,
        capabilities = capabilities,
        scriptTabs = scriptTabs,
        dialogPickedPath = dialogPickedPath,
        dialogPickedContent = dialogPickedContent,
        onDialogPickedPathConsumed = onDialogPickedPathConsumed,
        onScriptTabConfirmed = onScriptTabConfirmed,
        onScriptTabRemoved = onScriptTabRemoved,
        initialStructureRanges = ranges,
        fileTypeLabelOverride = presentation.displayLabel,
        initialActiveTab = presentation.tables.firstOrNull()?.id ?: "Sections",
        onLegacyReparseRequested = onLegacyReparseRequested,
        workerNativeImageToolbarPresentation = nativeImageToolbar,
        workerSizeTooltip = sizeTooltip,
        scriptExecution = scriptExecution,
    )
}

private fun ParserWorkerPresentation.workerSizeTooltip(): List<Pair<String, String>>? {
    val top = tables.firstOrNull { it.id == "Top" } ?: return null
    val typeColumn = top.columns.indexOf("Type")
    val sizeColumn = top.columns.indexOf("Size")
    if (typeColumn < 0 || sizeColumn < 0) return null
    val grouped = linkedMapOf<String, Long>()
    for (record in top.records) {
        val name = record.values.getOrNull(typeColumn) ?: return null
        val size = record.numericValues.getOrNull(sizeColumn) ?: return null
        if (size < 0 || size % 1.0 != 0.0 || size > Int.MAX_VALUE) return null
        grouped[name] = (grouped[name] ?: 0L) + size.toLong()
    }
    return grouped.entries.sortedByDescending { it.value }.map { (name, size) ->
        val count = top.records.count { it.values.getOrNull(typeColumn) == name }
        val label = if (count > 1) "$name (×$count)" else name
        label to formatSize(size.toInt())
    }
}

@Composable
private fun LegacyWasmTab(label: String, onLegacyWasmViewRequested: (String) -> Unit) {
    LaunchedEffect(label) {
        onLegacyWasmViewRequested(label)
    }
}

@Composable
private fun WorkerWasmPartialPanel(partial: ParserWorkerPartial, content: @Composable () -> Unit) {
    if (partial.partial) {
        Div({ attr("data-parser-worker-partial", partial.reasons.joinToString(",")) }) {
            Text("Partial results")
        }
    }
    content()
}

@Composable
private fun WorkerWasmSummaryTab(
    presentation: ParserWorkerPresentation,
    selection: HexSelectionState,
) {
    SearchableTable(headers = listOf("Metric", "Value"), defaultSortColumn = -1) {
        summaryRows(presentation.summary).forEach { (metric, value) ->
            PanelTableRow {
                Td({ classes("cell") }) { Text(metric) }
                Td({ classes("cell") }) { Text(value) }
            }
        }
    }
    if (presentation.ranges.isNotEmpty()) {
        SearchableTable(
            headers = listOf("Section", "Offset", "Size"),
            columnAligns = listOf("left", "right", "right"),
            defaultSortColumn = 1,
        ) {
            presentation.ranges.forEach { range ->
                val end = range.offset + range.size - 1
                PanelTableRow(
                    isHighlighted = selection.hasSelection &&
                        selection.selectionStart == range.offset && selection.selectionEnd == end,
                    onClick = {
                        selection.anchorIndex = range.offset
                        selection.activeIndex = end
                        selection.cursorIndex = range.offset
                        scrollHexViewerTo(range.offset)
                    },
                ) {
                    Td({ classes("cell") }) { Text(range.label) }
                    Td({ classes("cell-right") }) { Text("0x${range.offset.toString(16)}") }
                    Td({ classes("cell-right") }) { Text("${range.size} bytes") }
                }
            }
        }
    }
    if (presentation.summary.graalWebImage) {
        val modules = presentation.summary.graalWebImageMatchedImportModules.sorted().joinToString(", ")
        Div({
            style {
                property("margin-top", "8px")
                property("padding-top", "6px")
                property("border-top", "1px solid var(--hexana-border, #333)")
            }
        }) {
            Div({
                style {
                    property("font-weight", "600")
                    property("margin-bottom", "2px")
                }
            }) { Text("Toolchain: GraalVM Web Image") }
            Div({
                style {
                    property("color", "var(--vscode-descriptionForeground, #888)")
                    property("font-size", "11px")
                }
            }) { Text("via: $modules") }
        }
    }
}

private fun summaryRows(summary: ParserWorkerSummary): List<Pair<String, String>> = buildList {
    add("Sections" to summary.sectionCount.toString())
    add("Imports" to summary.importCount.toString())
    add("Exports" to summary.exportCount.toString())
    if (summary.definedFunctionCount > 0) add("Functions" to summary.definedFunctionCount.toString())
    if (summary.codeBodyCount > 0) add("Code bodies" to summary.codeBodyCount.toString())
    if (summary.moduleCount > 0) add("Modules" to summary.moduleCount.toString())
    if (summary.proposalCount > 0) add("Proposals" to summary.proposalCount.toString())
}

@Composable
private fun WorkerWasmTableTab(table: ParserWorkerTable, selection: HexSelectionState) {
    val descriptor = remember(table) { table.toTableTabDescriptor() }
    TableTabContent(descriptor, selection)
}

private fun ParserWorkerTable.toTableTabDescriptor(): TableTabDescriptor {
    fun columnsFor(records: List<ParserWorkerRecord>): List<ColumnDescriptor> = columns.mapIndexed { columnIndex, name ->
        ColumnDescriptor(name) { index -> workerCellValue(records[index], columnIndex) }
    }

    fun selectionFor(records: List<ParserWorkerRecord>): (Int) -> RowSelectionData? = { index ->
        records.getOrNull(index)?.let { record ->
            if (record.offset != null && record.size != null) RowSelectionData(record.offset, record.size) else null
        }
    }

    fun childRowsFor(records: List<ParserWorkerRecord>): TableTabChildRows = TableTabChildRows(
        columns = columnsFor(records),
        rowCount = records.size,
        rowSelectionProvider = selectionFor(records),
        childrenProvider = { index ->
            records.getOrNull(index)?.children?.takeIf { it.isNotEmpty() }?.let(::childRowsFor)
        },
    )

    val hasChildren = records.any { it.children.isNotEmpty() }
    return TableTabDescriptor(
        name = id,
        columns = columnsFor(records),
        rowCount = records.size,
        defaultSortColumn = defaultSortColumn,
        defaultSortAscending = defaultSortAscending,
        rowSelectionProvider = selectionFor(records),
        prologueText = prologueText,
        emptyMessage = emptyMessage,
        childrenProvider = if (hasChildren) { index ->
            records.getOrNull(index)?.children?.takeIf { it.isNotEmpty() }?.let(::childRowsFor)
        } else null,
        alwaysShowSearch = alwaysShowSearch,
        searchPlaceholder = searchPlaceholder,
        showIdColumn = showIdColumn,
        predefinedFilters = filters.map { filter ->
            val matching = filter.matchingRootRows.toSet()
            PredefinedFilter(filter.label) { it in matching }
        },
        defaultFilterShowAll = defaultFilterShowAll,
    )
}

private fun workerCellValue(record: ParserWorkerRecord, columnIndex: Int): CellValue<*> {
    val representation = record.values[columnIndex]
    val numeric = record.numericValues[columnIndex] ?: return CellValue(representation)
    return if (numeric % 1.0 == 0.0 && numeric in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        CellValue(numeric.toInt(), representation)
    } else {
        CellValue(numeric, representation)
    }
}
