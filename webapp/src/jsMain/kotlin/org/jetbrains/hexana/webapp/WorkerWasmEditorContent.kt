package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.vscode.BinaryEditorContent
import org.jetbrains.hexana.vscode.ComponentDependencyGraphEdgeData
import org.jetbrains.hexana.vscode.ComponentDependencyGraphNodeData
import org.jetbrains.hexana.vscode.DependencyGraphTabContent
import org.jetbrains.hexana.vscode.HexSelectionState
import org.jetbrains.hexana.vscode.PanelTab
import org.jetbrains.hexana.vscode.PanelTableRow
import org.jetbrains.hexana.vscode.SearchableTable
import org.jetbrains.hexana.vscode.ScriptTabState
import org.jetbrains.hexana.vscode.WebUiCapabilities
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
    val provider = remember(bytes) { GenericBinaryProvider(bytes.toCommonByteBuffer()) }
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
            add(PanelTab("Dependencies", padding = false) { _ ->
                WorkerWasmPartialPanel(response.partial) {
                    DependencyGraphTabContent(
                        nodes = graph.nodes.map { node ->
                            ComponentDependencyGraphNodeData(node.id, node.kind, node.label, node.sublabel)
                        },
                        edges = graph.edges.map { edge ->
                            ComponentDependencyGraphEdgeData(edge.fromId, edge.toId, edge.label)
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
        fileTypeLabelOverride = when (presentation.format) {
            "core_wasm" -> "Core Wasm"
            "wasm_component" -> "Component Model"
            else -> null
        },
        onLegacyReparseRequested = onLegacyWasmReparseRequested,
        workerWasmToolbarPresentation = toolbarPresentation,
    )
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
    SearchableTable(headers = table.columns, defaultSortColumn = -1) {
        table.records.forEach { record ->
            val hasRange = record.offset != null && record.size != null
            val end = if (hasRange) record.offset!! + record.size!! - 1 else -1
            PanelTableRow(
                isHighlighted = hasRange && selection.hasSelection &&
                    selection.selectionStart == record.offset && selection.selectionEnd == end,
                onClick = {
                    if (hasRange) {
                        val offset = record.offset!!
                        selection.anchorIndex = offset
                        selection.activeIndex = end
                        selection.cursorIndex = offset
                        scrollHexViewerTo(offset)
                    }
                },
            ) {
                record.values.forEach { value ->
                    Td({ classes("cell") }) { Text(value) }
                }
            }
        }
    }
}
