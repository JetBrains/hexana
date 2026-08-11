package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeImageInfo
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.fileTypeDescription
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

/** Worker-produced Wasm metadata that preserves toolbar behavior without a Window parser provider. */
data class WorkerWasmToolbarPresentation(
    val isComponent: Boolean,
    val runnableExportName: String? = null,
    val proposalIds: List<String> = emptyList(),
)

/** Worker-produced native-image metadata for the read-only initial browser route. */
data class WorkerNativeImageToolbarPresentation(
    val version: String? = null,
    val edition: String = "unknown",
    val platform: String? = null,
    val hasSbom: Boolean = false,
)

private data class NativeImageBadgePresentation(
    val version: String?,
    val edition: String?,
    val platform: String?,
    val hasSbom: Boolean,
)

/**
 * Top toolbar that shows the file name, size, detected binary kind,
 * an optional run button for runnable modules, and edit-mode controls
 * (Discard / Overwrite / Save a copy / Undo overwrite) when [editState]
 * is provided.
 */
@Composable
fun EditorToolbar(
    fileName: String,
    parentFileName: String? = null,
    size: Int,
    provider: BinaryProvider,
    onSizeClick: (() -> Unit)? = null,
    editState: HexEditState? = null,
    overwriteShadow: OverwriteShadow? = null,
    onOverwriteShadowChanged: ((OverwriteShadow?) -> Unit)? = null,
    onReparseAfterOverwrite: () -> Unit = {},
    onOpenSbomTab: (() -> Unit)? = null,
    fileTypeLabelOverride: String? = null,
    workerWasmToolbarPresentation: WorkerWasmToolbarPresentation? = null,
    workerNativeImageToolbarPresentation: WorkerNativeImageToolbarPresentation? = null,
    workerSizeTooltip: List<Pair<String, String>>? = null,
    isDecompressed: Boolean = false,
) {
    val isSubmodule = parentFileName != null || isSubmoduleFileName(fileName)

    val sizeTooltip = remember(provider, workerSizeTooltip) {
        workerSizeTooltip ?: buildSizeTooltip(provider)
    }

    val runnableExportName = remember(provider, isSubmodule, workerWasmToolbarPresentation) {
        if (isSubmodule) return@remember null
        workerWasmToolbarPresentation?.let { return@remember it.runnableExportName }
        when (provider) {
            is WasmBinaryProvider -> {
                if (!provider.canProvideWasmModule()) return@remember null
                val startExport = provider.exports.firstOrNull { export ->
                    export is WasmFunctionExportRecordEntry &&
                        (export.name == "_start" || export.name == "_initialize")
                }
                startExport?.name
            }
            is WasmComponentBinaryProvider -> {
                if (!provider.canProvideWasmModule() || !hasWasiCliRunExport(provider)) return@remember null
                "wasi:cli/run"
            }
            else -> null
        }
    }

    val host = LocalHost.current
    val isRunnable = runnableExportName != null && host.canRunExport
    val isComponent = workerWasmToolbarPresentation?.isComponent ?: provider is WasmComponentBinaryProvider

    // Lazy native-image detection: nativeImageInfo is an expensive lazy val.
    // Mirror the same pattern used in HexanaPanel so the cached value is shared.
    var nativeImageInfo by remember(provider) { mutableStateOf<NativeImageInfo?>(null) }
    LaunchedEffect(provider, workerNativeImageToolbarPresentation) {
        nativeImageInfo = null
        if (workerNativeImageToolbarPresentation == null && provider is NativeBinaryProvider) {
            nativeImageInfo = provider.nativeImageInfo
        }
    }

    // Which action the toolbar button triggered: null = hidden, "run" / "debug" = show only that button
    var toolbarAction by remember { mutableStateOf<String?>(null) }

    if (toolbarAction != null && runnableExportName != null) {
        ProgramArgsDialog(
            exportName = runnableExportName,
            isComponent = isComponent,
            forcedAction = toolbarAction,
            onConfirm = { result ->
                host.runExport(
                    exportName = "",
                    invokeArgs = "",
                    isComponent = isComponent,
                    programArgs = result.programArgs,
                    wasmBytes = provider.bytes.toByteArray(),
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

        // --- Edit mode controls ---
        // Reading modificationStamp subscribes this composable to edit changes.
        val hasEdits = editState?.let { @Suppress("UNUSED_VARIABLE") val s = it.modificationStamp; it.hasEdits } ?: false
        val isPostOverwrite = overwriteShadow != null
        val showEditControls = (editState != null && onOverwriteShadowChanged != null) && (hasEdits || isPostOverwrite)
        val showNormalContent = !showEditControls || isPostOverwrite

        if (showEditControls && editState != null && onOverwriteShadowChanged != null) {
            val isFileScheme = !isSubmodule
            val canOverwrite = hasEdits && isFileScheme && !isDecompressed

                Div({
                    classes("hexana-edit-controls")
                    style {
                        property("display", "inline-flex")
                        property("align-items", "center")
                        property("gap", "8px")
                        property("padding", "2px 8px")
                        property("border-radius", "4px")
                    }
                }) {
                    if (isPostOverwrite) {
                        EditToolbarButton(
                            label = "Undo overwrite",
                            enabled = true,
                            primary = false,
                        ) {
                            val restoredBytes = overwriteShadow!!.applyTo(editState.getEditedBytes())
                            host.overwriteFile(restoredBytes)
                            editState.discardAll()
                            onOverwriteShadowChanged(null)
                            onReparseAfterOverwrite()
                        }
                    } else {
                        EditToolbarButton(
                            label = "Discard",
                            enabled = hasEdits,
                            primary = false,
                        ) {
                            editState.discardAll()
                            onReparseAfterOverwrite()
                        }
                        EditToolbarButton(
                            label = "Overwrite",
                            enabled = canOverwrite,
                            primary = true,
                        ) {
                            val shadow = editState.captureOverwriteShadow()
                            val editedBytes = editState.getEditedBytes()
                            host.overwriteFile(editedBytes)
                            onOverwriteShadowChanged(shadow)
                            editState.markSaved()
                            onReparseAfterOverwrite()
                        }
                        val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
                        val finalizedName = computeFinalizedName(baseName, stripGz = isDecompressed)
                        EditToolbarButton(
                            label = "Save a copy \u2192 $finalizedName",
                            enabled = hasEdits && isFileScheme,
                            primary = false,
                        ) {
                            val editedBytes = editState.getEditedBytes()
                            host.saveCopy(finalizedName, editedBytes)
                        }
                    }
                }
        }

        if (showNormalContent) {
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
                Text(fileTypeLabelOverride ?: fileTypeDescription(provider, fileName))
            }

            if (isSubmodule) {
                val parentName = parentFileName ?: parentFileNameOf(fileName) ?: "component"
                Span({
                    style {
                        property("cursor", "pointer")
                        property("font-size", "11px")
                        color(Color("var(--vscode-textLink-foreground, #3794FF)"))
                    }
                    onClick { host.navigateToParent() }
                }) {
                    val parentExt = parentName.substringAfterLast('.', "").lowercase()
                    val parentLabel = if (parentExt in setOf("jar", "zip", "war", "ear", "apk", "aar", "tar", "tgz")) {
                        "Entry in $parentName"
                    } else {
                        "Submodule of $parentName"
                    }
                    Text(parentLabel)
                }

                // "Save As…" button for submodule / archive entry content
                Span({
                    attr("title", "Save embedded content to a new file")
                    style {
                        property("cursor", "pointer")
                        property("display", "inline-flex")
                        property("align-items", "center")
                        property("padding", "2px 8px")
                        property("border-radius", "4px")
                        property("border", "1px solid var(--vscode-button-secondaryBorder, var(--vscode-contrastBorder, transparent))")
                        property("background", "var(--vscode-button-secondaryBackground, #3A3D41)")
                        property("color", "var(--vscode-button-secondaryForeground, #fff)")
                        property("font-size", "11px")
                        property("line-height", "1")
                        property("user-select", "none")
                    }
                    onClick { event ->
                        event.stopPropagation()
                        host.saveAs()
                    }
                }) {
                    Text("Save As\u2026")
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

            val nativeImageBadge = workerNativeImageToolbarPresentation?.let { presentation ->
                NativeImageBadgePresentation(
                    version = presentation.version,
                    edition = presentation.edition,
                    platform = presentation.platform,
                    hasSbom = presentation.hasSbom,
                )
            } ?: nativeImageInfo?.takeIf { it.isNativeImage }?.toBadgePresentation()
            if (nativeImageBadge != null) {
                NativeImageBadges(nativeImageBadge, onOpenSbomTab = onOpenSbomTab)
            }

            val proposals = remember(provider, workerWasmToolbarPresentation) {
                workerWasmToolbarPresentation?.let { presentation ->
                    return@remember presentation.proposalIds.mapNotNull { id ->
                        WasmProposal.entries.firstOrNull { it.name == id }
                    }
                }
                if (provider !is WasmBinaryProvider) return@remember emptyList()
                if (!provider.canProvideWasmModule()) return@remember emptyList()
                provider.usedProposals.sorted()
            }

            if (proposals.isNotEmpty()) {
                ProposalBadges(proposals)
            }
        }

        // Spacer to push Share Feedback to the right
        Div({
            style {
                property("flex", "1")
            }
        }) {}

        ShareFeedbackButton()
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

private fun buildSizeTooltip(provider: BinaryProvider): List<Pair<String, String>>? {
    val sizeAnalysis = provider.sizeAnalysis ?: return null
    val children = sizeAnalysis.children
    if (children.isEmpty()) return null

    val grouped = linkedMapOf<String, MutableList<BinarySizeNode>>()
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


/**
 * `<base>.edited.<ext>` next to the original. Plain `<name>.edited` if there
 * is no extension.
 */
private fun computeFinalizedName(originalName: String, stripGz: Boolean = false): String {
    val baseName = if (stripGz && originalName.endsWith(".gz", ignoreCase = true)) {
        originalName.substring(0, originalName.length - 3)
    } else {
        originalName
    }
    val dot = baseName.lastIndexOf('.')
    return if (dot < 0) "$baseName.edited"
    else baseName.substring(0, dot) + ".edited" + baseName.substring(dot)
}

/**
 * Small button for the edit-mode toolbar strip.
 */
@Composable
private fun EditToolbarButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Span({
        classes("hexana-edit-btn")
        if (primary) classes("hexana-edit-btn-primary")
        if (!enabled) classes("hexana-edit-btn-disabled")
        if (enabled) {
            onClick { event ->
                event.stopPropagation()
                onClick()
            }
        }
    }) {
        Text(label)
    }
}

private const val GITHUB_ISSUES_URL = "https://github.com/JetBrains/hexana/issues"
private const val VSCODE_MARKETPLACE_REVIEW_URL =
    "https://marketplace.visualstudio.com/items?itemName=JetBrains.hexana-wasm&ssr=false#review-details"
private const val OPENVSX_REVIEW_URL =
    "https://open-vsx.org/extension/JetBrains/hexana-wasm/reviews"

/**
 * "Share Feedback" button with a dropdown offering links to report a bug,
 * rate on VS Code Marketplace, or rate on Open VSX.
 */
@Composable
private fun ShareFeedbackButton() {
    val host = LocalHost.current
    var dropdownVisible by remember { mutableStateOf(false) }

    Div({
        style {
            property("position", "relative")
            property("display", "inline-flex")
            property("flex-shrink", "0")
        }
    }) {
        Span({
            classes("hexana-share-feedback-btn")
            onClick { event ->
                event.stopPropagation()
                dropdownVisible = !dropdownVisible
            }
        }) {
            Text("Share Feedback")
        }

        if (dropdownVisible) {
            // Invisible overlay to close dropdown when clicking outside
            Div({
                style {
                    property("position", "fixed")
                    property("top", "0")
                    property("left", "0")
                    property("right", "0")
                    property("bottom", "0")
                    property("z-index", "999")
                }
                onClick { dropdownVisible = false }
            }) {}

            Div({
                classes("hexana-share-feedback-dropdown")
            }) {
                ShareFeedbackDropdownItem(
                    title = "Report a bug",
                    subtitle = "Open a GitHub issue with reproduction details",
                ) {
                    host.openUrl(GITHUB_ISSUES_URL)
                    dropdownVisible = false
                }
                ShareFeedbackDropdownItem(
                    title = "Rate on VS Code Marketplace",
                    subtitle = "Leave a review on the VS Code Marketplace",
                ) {
                    host.openUrl(VSCODE_MARKETPLACE_REVIEW_URL)
                    dropdownVisible = false
                }
                ShareFeedbackDropdownItem(
                    title = "Rate on Open VSX",
                    subtitle = "Leave a review on the Open VSX Registry",
                ) {
                    host.openUrl(OPENVSX_REVIEW_URL)
                    dropdownVisible = false
                }
            }
        }
    }
}

/**
 * Renders "Native Image" and optionally "SBOM" badges for a GraalVM native binary.
 *
 * Matches the IntelliJ [FileDescriptionBar.NativeImageBadges] rendering using
 * VS Code CSS variables in place of Compose Desktop colors.
 */
private fun NativeImageInfo.toBadgePresentation(): NativeImageBadgePresentation = NativeImageBadgePresentation(
    version = version,
    edition = when (edition) {
        NativeImageInfo.Edition.COMMUNITY -> "community"
        NativeImageInfo.Edition.ENTERPRISE -> "enterprise"
        NativeImageInfo.Edition.UNKNOWN -> "unknown"
    },
    platform = platform,
    hasSbom = sbom != null,
)

@Composable
private fun NativeImageBadges(info: NativeImageBadgePresentation, onOpenSbomTab: (() -> Unit)?) {
    val tooltipText = buildString {
        append("GraalVM Native Image")
        info.version?.let { append('\n').append(it) }
        val edition = when (info.edition) {
            "community" -> "Community"
            "enterprise" -> "Enterprise"
            else -> null
        }
        edition?.let { append('\n').append(it) }
        info.platform?.let { append('\n').append(it) }
    }

    Span({
        classes("hexana-badge", "hexana-ni-badge")
        attr("title", tooltipText)
        style {
            property("padding", "2px 8px")
            property("border-radius", "4px")
            property("font-size", "11px")
            property("background", "var(--hexana-ni-badge-bg, #3A335740)")
            property("color", "var(--hexana-ni-badge-fg, #C4B5F0)")
        }
    }) {
        Text("Native Image")
    }

    if (info.hasSbom) {
        val hasSbomCallback = onOpenSbomTab != null
        Span({
            classes("hexana-badge", "hexana-sbom-badge")
            attr("title", if (hasSbomCallback) "Open the CycloneDX SBOM viewer" else "CycloneDX SBOM embedded")
            if (hasSbomCallback) {
                style {
                    property("padding", "2px 8px")
                    property("border-radius", "4px")
                    property("font-size", "11px")
                    property("font-weight", "600")
                    property("background", "var(--hexana-ni-badge-bg, #3A335740)")
                    property("color", "var(--hexana-ni-badge-fg, #C4B5F0)")
                    property("cursor", "pointer")
                }
                onClick { onOpenSbomTab!!() }
            } else {
                style {
                    property("padding", "2px 8px")
                    property("border-radius", "4px")
                    property("font-size", "11px")
                    property("font-weight", "600")
                    property("background", "var(--hexana-ni-badge-bg, #3A335740)")
                    property("color", "var(--hexana-ni-badge-fg, #C4B5F0)")
                }
            }
        }) {
            Text("SBOM")
        }
    }
}

@Composable
private fun ShareFeedbackDropdownItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Div({
        classes("hexana-share-feedback-item")
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Div({
            style {
                property("font-size", "12px")
                property("font-weight", "500")
            }
        }) {
            Text(title)
        }
        Div({
            style {
                property("font-size", "11px")
                property("opacity", "0.7")
                property("margin-top", "2px")
            }
        }) {
            Text(subtitle)
        }
    }
}
