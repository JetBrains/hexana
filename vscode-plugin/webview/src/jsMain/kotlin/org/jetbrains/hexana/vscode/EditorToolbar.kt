package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

/**
 * Top toolbar that shows the file name, size, detected binary kind,
 * and an optional run button for runnable modules.
 */
@Composable
fun EditorToolbar(
    fileName: String,
    size: Int,
    providerType: BinaryProviderType,
    buffer: CommonByteBuffer? = null,
    wasmBytes: ByteArray? = null,
    onSizeClick: (() -> Unit)? = null,
) {
    val isSubmodule = isSubmoduleFileName(fileName)

    val sizeTooltip = remember(buffer, providerType) {
        buildSizeTooltip(buffer, providerType)
    }

    val runnableExportName = remember(buffer, providerType, isSubmodule) {
        if (buffer == null || isSubmodule) return@remember null
        when (providerType) {
            is BinaryProviderType.Wasm -> {
                buffer.position(0)
                val provider = WasmBinaryProvider(buffer)
                if (!provider.canProvideWasmModule()) return@remember null
                val startExport = provider.exports.firstOrNull { export ->
                    export is WasmFunctionExportRecordEntry &&
                        (export.name == "_start" || export.name == "_initialize")
                }
                startExport?.name
            }
            is BinaryProviderType.WasmComponent -> {
                buffer.position(0)
                val provider = WasmComponentBinaryProvider(buffer)
                if (!provider.canProvideWasmModule() || !hasWasiCliRunExport(provider)) return@remember null
                "wasi:cli/run"
            }
            else -> null
        }
    }

    val isRunnable = runnableExportName != null
    val isComponent = providerType is BinaryProviderType.WasmComponent

    // Which action the toolbar button triggered: null = hidden, "run" / "debug" = show only that button
    var toolbarAction by remember { mutableStateOf<String?>(null) }

    if (toolbarAction != null && runnableExportName != null) {
        ProgramArgsDialog(
            exportName = runnableExportName,
            isComponent = isComponent,
            forcedAction = toolbarAction,
            onConfirm = { result ->
                postRunExportToHost(
                    exportName = "",
                    isComponent = isComponent,
                    programArgs = result.programArgs,
                    wasmBytes = wasmBytes,
                    runtime = result.runtime,
                    action = result.action,
                )
                toolbarAction = null
            },
            onCancel = { toolbarAction = null },
        )
    }

    Div({
        classes("hexana-toolbar")
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "16px")
            property("padding", "6px 12px")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("flex-shrink", "0")
            property("overflow", "visible")
            property("position", "relative")
            property("z-index", "100")
        }
    }) {
        Span({ style { property("font-weight", "600") } }) {
            Text(fileName.substringAfterLast('/').substringAfterLast('\\'))
        }

        var sizeHovered by remember { mutableStateOf(false) }

        Div({
            style {
                property("display", "inline-flex")
                property("align-items", "center")
                property("position", "relative")
                if (onSizeClick != null) property("cursor", "pointer")
            }
            onMouseEnter { sizeHovered = true }
            onMouseLeave { sizeHovered = false }
            if (onSizeClick != null) onClick { onSizeClick() }
        }) {
            Span({ style { property("opacity", "0.7") } }) {
                Text(formatSize(size))
            }
            if (sizeTooltip != null && sizeHovered) {
                Div({
                    style {
                        property("position", "absolute")
                        property("top", "100%")
                        property("left", "0")
                        property("z-index", "1000")
                        property("margin-top", "4px")
                        property("padding", "8px 12px")
                        property("border-radius", "4px")
                        property("background", "var(--vscode-editorHoverWidget-background, #2d2d30)")
                        property("border", "1px solid var(--vscode-editorHoverWidget-border, #454545)")
                        property("color", "var(--vscode-editorHoverWidget-foreground, #ccc)")
                        property("font-size", "12px")
                        property("white-space", "nowrap")
                        property("box-shadow", "0 2px 8px rgba(0,0,0,0.3)")
                        property("pointer-events", "none")
                    }
                }) {
                    Div({
                        style {
                            property("margin-bottom", "6px")
                            property("opacity", "0.8")
                        }
                    }) {
                        Text("Click to see the full analysis in the \"Top\" tab below")
                    }
                    Table({
                        style {
                            property("border-collapse", "collapse")
                            property("width", "100%")
                        }
                    }) {
                        sizeTooltip.forEach { (label, size) ->
                            Tr {
                                Td({
                                    style {
                                        property("padding", "1px 16px 1px 0")
                                        property("text-align", "left")
                                    }
                                }) { Text(label) }
                                Td({
                                    style {
                                        property("padding", "1px 0")
                                        property("text-align", "right")
                                    }
                                }) { Text(size) }
                            }
                        }
                    }
                }
            }
        }

        Span({
            classes("hexana-badge")
            style {
                property("padding", "2px 8px")
                property("border-radius", "4px")
                property("font-size", "11px")
                property("background", "var(--vscode-badge-background, #4D4D4D)")
                property("color", "var(--vscode-badge-foreground, #FFF)")
            }
        }) {
            Text(
                when (providerType) {
                    is BinaryProviderType.Wasm -> "Core Wasm"
                    is BinaryProviderType.WasmComponent -> "Component Model"
                    is BinaryProviderType.Generic -> "Binary"
                }
            )
        }

        if (isSubmodule) {
            val parentName = parentFileNameOf(fileName) ?: "component"
            Span({
                style {
                    property("cursor", "pointer")
                    property("font-size", "11px")
                    color(Color("var(--vscode-textLink-foreground, #3794FF)"))
                }
                onClick { postNavigateToParent() }
            }) {
                Text("Submodule of $parentName")
            }
        }

        if (isRunnable) {
            ToolbarRunButton(
                tooltip = "Run Wasm module",
                onClick = { toolbarAction = "run" }
            )
            ToolbarDebugButton(
                tooltip = "Debug Wasm module",
                onClick = { toolbarAction = "debug" }
            )
        }

        val proposals = remember(buffer, providerType) {
            if (buffer == null || providerType !is BinaryProviderType.Wasm) return@remember emptyList()
            buffer.position(0)
            val provider = WasmBinaryProvider(buffer)
            if (!provider.canProvideWasmModule()) return@remember emptyList()
            provider.usedProposals.sorted()
        }

        if (proposals.isNotEmpty()) {
            ProposalBadges(proposals)
        }
    }
}

/**
 * Button-styled run control for the editor toolbar.
 * Displays a play arrow followed by a "Run" label, with a visible
 * border and hover highlight so it reads as a clickable button.
 */
@Composable
private fun ToolbarRunButton(tooltip: String = "Run", onClick: () -> Unit) {
    Span({
        attr("title", tooltip)
        style {
            property("cursor", "pointer")
            property("display", "inline-flex")
            property("align-items", "center")
            property("gap", "4px")
            property("padding", "3px 10px 3px 6px")
            property("border-radius", "4px")
            property("border", "1px solid var(--vscode-button-border, var(--vscode-contrastBorder, transparent))")
            property("background", "var(--vscode-button-background, #0e639c)")
            property("color", "var(--vscode-button-foreground, #fff)")
            property("font-size", "12px")
            property("line-height", "1")
            property("user-select", "none")
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Span({
            style {
                property("font-size", "11px")
                property("display", "inline-flex")
                property("align-items", "center")
            }
        }) {
            Text("\u25B6")
        }
        Span {
            Text("Run")
        }
    }
}

/**
 * Button-styled debug control for the editor toolbar.
 * Displays a bug icon followed by a "Debug" label, styled with the
 * same orange colour used in the dialog debug button.
 */
@Composable
private fun ToolbarDebugButton(tooltip: String = "Debug", onClick: () -> Unit) {
    Span({
        attr("title", tooltip)
        classes("hexana-toolbar-debug-btn")
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Span({ classes("hexana-toolbar-debug-icon") })
        Span {
            Text("Debug")
        }
    }
}

private const val MAX_INDIVIDUAL_PROPOSAL_BADGES = 3

@Composable
private fun ProposalBadges(proposals: List<WasmProposal>) {
    if (proposals.size <= MAX_INDIVIDUAL_PROPOSAL_BADGES) {
        for (proposal in proposals) {
            Span({
                classes("hexana-proposal-badge")
                attr("title", "Uses ${proposal.displayName} proposal")
                style {
                    property("padding", "2px 8px")
                    property("border-radius", "4px")
                    property("font-size", "11px")
                    property("background", "var(--hexana-proposal-badge-bg, #3D5040)")
                    property("color", "var(--hexana-proposal-badge-fg, #A8D8B0)")
                }
            }) {
                Text(proposal.displayName)
            }
        }
    } else {
        val tooltipText = proposals.joinToString("\n") { it.displayName }
        Span({
            classes("hexana-proposal-badge")
            attr("title", tooltipText)
            style {
                property("padding", "2px 8px")
                property("border-radius", "4px")
                property("font-size", "11px")
                property("background", "var(--hexana-proposal-badge-bg, #3D5040)")
                property("color", "var(--hexana-proposal-badge-fg, #A8D8B0)")
            }
        }) {
            Text("${proposals.size} proposals")
        }
    }
}

private fun buildSizeTooltip(buffer: CommonByteBuffer?, providerType: BinaryProviderType): List<Pair<String, String>>? {
    if (buffer == null) return null
    val sizeAnalysis: SizeNode.Module? = when (providerType) {
        is BinaryProviderType.Wasm -> {
            buffer.position(0)
            WasmBinaryProvider(buffer).sizeAnalysis
        }
        is BinaryProviderType.WasmComponent -> {
            buffer.position(0)
            WasmComponentBinaryProvider(buffer).sizeAnalysis
        }
        else -> null
    }
    val children = sizeAnalysis?.children ?: return null
    if (children.isEmpty()) return null

    val grouped = linkedMapOf<String, MutableList<SizeNode>>()
    for (child in children) {
        grouped.getOrPut(child.name) { mutableListOf() }.add(child)
    }

    return grouped.map { (name, nodes) ->
        val totalSize = nodes.sumOf { it.size }
        val label = if (nodes.size > 1) "$name (\u00d7${nodes.size})" else name
        label to totalSize
    }.sortedByDescending { it.second }
     .map { (label, size) -> label to formatSize(size) }
}

private fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        val mb = bytes.toDouble() / (1024 * 1024)
        val rounded = (mb * 10).toLong() / 10.0
        "$rounded MB"
    }
}
