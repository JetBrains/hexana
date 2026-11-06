package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.features.ParserRef

import org.jetbrains.hexana.shared.inspections.BinaryProblem
import org.jetbrains.hexana.shared.inspections.ProblemSeverity
import org.jetbrains.hexana.shared.inspections.counts
import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeImageInfo
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.fileTypeDescription
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.shared.features.ProposalCompatibility
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import org.jetbrains.hexana.shared.features.unsupportedProposalsSummary
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
    /** Size on disk when only [size] bytes of the file are loaded; shown in place of [size]. */
    fileSize: Long? = null,
    editState: HexEditState? = null,
    overwriteShadow: OverwriteShadow? = null,
    onOverwriteShadowChanged: ((OverwriteShadow?) -> Unit)? = null,
    onReparseAfterOverwrite: () -> Unit = {},
    onOpenSbomTab: (() -> Unit)? = null,
    /**
     * The feature catalog paired with the runtimes declared in `.hexana/runtimes.json`, when
     * the host has them. `null` leaves the proposals badges purely informational, which is
     * how the browser webapp renders them.
     */
    runtimeCompatibility: WasmRuntimeCompatibility? = null,
    /** Selects the Compatibility tab, where the matrix and the documentation links live. */
    onOpenCompatibilityTab: (() -> Unit)? = null,
    /**
     * What the problems badge counts. Inspected by the caller, from the provider the Problems
     * tab is built from and outside composition; this toolbar's own [provider] is the one
     * the file opened with, which a reparse after an edit replaces.
     */
    problems: List<BinaryProblem> = emptyList(),
    /**
     * Selects the Problems tab. `null` hides the problems badge — the webapp's worker route
     * passes null, because counting would parse the binary on the UI thread it offloads.
     */
    onOpenProblemsTab: (() -> Unit)? = null,
    fileTypeLabelOverride: String? = null,
    workerWasmToolbarPresentation: WorkerWasmToolbarPresentation? = null,
    workerNativeImageToolbarPresentation: WorkerNativeImageToolbarPresentation? = null,
    workerSizeTooltip: List<Pair<String, String>>? = null,
    isDecompressed: Boolean = false,
    /**
     * Number of sessions in the key log currently loaded for a pcap capture,
     * derived by the caller under its key-log generation counter. Read here
     * only to label the button, never by touching the provider.
     */
    sslKeyLogSessionCount: Int = 0,
    /** Turns the format badge into a parser picker; see [ParserChoice]. */
    parserChoice: ParserChoice? = null,
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
                Text(if (fileSize != null) "${formatSizeLong(fileSize)} (first ${formatSize(size)} loaded)" else formatSize(size))
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
            FormatBadge(
                label = fileTypeLabelOverride ?: fileTypeDescription(
                    provider, fileName, (parserChoice?.chosen as? ParserRef.Builtin)?.parser,
                ),
                choice = parserChoice,
            )

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

            // Install and launch on an Android emulator. The host decides availability
            // (it alone can see the SDK); the toolbar only rules out archive entries.
            if (host.canRunApk && !isSubmodule && fileName.endsWith(".apk", ignoreCase = true)) {
                ToolbarRunButton(
                    tooltip = "Install and launch on an Android emulator",
                    onClick = { host.runApk() },
                )
            }

            // SSL key log import for pcap captures. Only offered by hosts that
            // can show a file picker; the browser webapp parses captures in a
            // worker and has no way to push a key log back into the provider.
            if (provider is PcapBinaryProvider && host.canPickSslKeyLog) {
                if (sslKeyLogSessionCount > 0) {
                    Span({
                        classes("hexana-badge")
                        // Unstyled it inherits the editor's body size, which
                        // reads as a headline next to the 11px badges and
                        // buttons it shares the toolbar with.
                        style {
                            property("font-size", "11px")
                            property("line-height", "1")
                        }
                    }) {
                        Text("\uD83D\uDD11 SSL keys loaded ($sslKeyLogSessionCount sessions)")
                    }
                }
                EditToolbarButton(
                    label = if (sslKeyLogSessionCount > 0) "Change Key Log\u2026" else "Load SSL Key Log\u2026",
                    enabled = true,
                    primary = false,
                    onClick = { host.pickSslKeyLog() },
                )
            }

            val proposals = remember(provider, workerWasmToolbarPresentation) {
                workerProposals(workerWasmToolbarPresentation) ?: providerProposals(provider)
            }

            if (proposals.isNotEmpty()) {
                val compatibility = remember(proposals, runtimeCompatibility) {
                    runtimeCompatibility
                        ?.takeIf { it.targets.targets.isNotEmpty() }
                        ?.evaluate(proposals)
                        .orEmpty()
                }
                ProposalBadges(proposals, compatibility, onOpenCompatibilityTab)
            }

            if (onOpenProblemsTab != null) {
                ProblemsBadge(problems, onOpenProblemsTab)
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

/** Proposals the worker already detected, or `null` when this host has no worker result. */
private fun workerProposals(presentation: WorkerWasmToolbarPresentation?): List<WasmProposal>? =
    presentation?.proposalIds?.mapNotNull { id -> WasmProposal.entries.firstOrNull { it.name == id } }

/** Proposals detected from a provider parsed in this window. */
private fun providerProposals(provider: BinaryProvider): List<WasmProposal> =
    if (provider is WasmBinaryProvider && provider.canProvideWasmModule()) {
        provider.usedProposals.sorted()
    } else {
        emptyList()
    }

/**
 * The proposals a module uses, as badges. A proposal a configured runtime cannot run turns
 * its badge (or the collapsed badge) warning-coloured, without an icon: [ProblemsBadge]
 * already carries the marker and the count, so this only says *which* proposal the problem
 * is about. The tooltip marks the offending proposal with ⚠. Mirrors the IntelliJ badges in
 * `FileDescriptionBar.kt`.
 */
@Composable
private fun ProposalBadges(
    proposals: List<WasmProposal>,
    compatibility: List<ProposalCompatibility>,
    onOpenCompatibilityTab: (() -> Unit)?,
) {
    val byProposal = remember(compatibility) { compatibility.associateBy { it.proposal } }

    if (proposals.size <= MAX_INDIVIDUAL_PROPOSAL_BADGES) {
        for (proposal in proposals) {
            val entry = byProposal[proposal]
            ProposalBadge(
                label = proposal.displayName,
                tooltip = proposalTooltip(proposal, entry, onOpenCompatibilityTab != null),
                hasWarning = entry?.hasWarning == true,
                onClick = onOpenCompatibilityTab,
            )
        }
    } else {
        // Collapsed form: one badge for the count, coloured when any proposal has a problem.
        ProposalBadge(
            label = "${proposals.size} proposals",
            tooltip = collapsedProposalTooltip(proposals, byProposal, onOpenCompatibilityTab != null),
            hasWarning = proposals.any { byProposal[it]?.hasWarning == true },
            onClick = onOpenCompatibilityTab,
        )
    }
}

@Composable
private fun ProposalBadge(
    label: String,
    tooltip: List<ProposalTooltipLine>,
    hasWarning: Boolean,
    onClick: (() -> Unit)?,
) {
    var hovered by remember { mutableStateOf(false) }
    Span({
        classes("hexana-proposal-badge")
        style {
            property("position", "relative")
            property("padding", "2px 8px")
            property("border-radius", "4px")
            property("font-size", "11px")
            if (hasWarning) {
                // The same colours as the problems badge's warning form.
                property("background", "var(--vscode-inputValidation-warningBackground, #5A4A2A)")
                property("color", "var(--vscode-editorWarning-foreground, #E0C070)")
            } else {
                // The CSS class carries no colours in either host stylesheet today, so the inline
                // fallbacks are what actually render.
                property("background", "var(--hexana-proposal-badge-bg, #3D5040)")
                property("color", "var(--hexana-proposal-badge-fg, #A8D8B0)")
            }
            if (onClick != null) property("cursor", "pointer")
        }
        onMouseEnter { hovered = true }
        onMouseLeave { hovered = false }
        if (onClick != null) {
            onClick { event ->
                event.stopPropagation()
                onClick()
            }
        }
    }) {
        Text(label)
        // A popover rather than a `title`, like the size label's: a native tooltip cannot
        // colour the lines that carry the warning.
        if (hovered) ProposalTooltipPopover(tooltip)
    }
}

/**
 * One line of a proposal tooltip.
 *
 * @param warning coloured with the editor's warning colour
 * @param indented a remark under the line before it
 * @param spaced preceded by a gap, separating it from the group above
 */
private class ProposalTooltipLine(
    val text: String,
    val warning: Boolean = false,
    val indented: Boolean = false,
    val spaced: Boolean = false,
)

@Composable
private fun ProposalTooltipPopover(lines: List<ProposalTooltipLine>) {
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
            // Wide enough for a runtime line on one row, narrow enough to wrap the remarks.
            property("width", "max-content")
            property("max-width", "360px")
            property("white-space", "normal")
            property("box-shadow", "0 2px 8px rgba(0,0,0,0.3)")
            property("pointer-events", "none")
        }
    }) {
        for (line in lines) {
            Div({
                style {
                    if (line.warning) property("color", "var(--vscode-editorWarning-foreground, #E0C070)")
                    if (line.indented) property("padding-left", "16px")
                    if (line.spaced) property("margin-top", "6px")
                }
            }) {
                Text(line.text)
            }
        }
    }
}

/** Most problems the badge tooltip lists before summarising the rest. */
private const val MAX_TOOLTIP_PROBLEMS = 5

/**
 * "2 errors, 1 warning", coloured by the worst severity, opening the Problems tab. Absent
 * when there are no errors or warnings: notes alone are not worth a toolbar marker. Mirrors
 * the IntelliJ badge in `FileDescriptionBar.kt`.
 */
@Composable
private fun ProblemsBadge(problems: List<BinaryProblem>, onClick: () -> Unit) {
    val counts = remember(problems) { problems.counts() }
    val severity = counts.highestSeverity?.takeIf { it != ProblemSeverity.Info } ?: return
    val isError = severity == ProblemSeverity.Error
    Span({
        classes("hexana-problems-badge")
        attr("title", problemsTooltip(problems))
        style {
            property("padding", "2px 8px")
            property("border-radius", "4px")
            property("font-size", "11px")
            property("cursor", "pointer")
            if (isError) {
                property("background", "var(--vscode-inputValidation-errorBackground, #5C3232)")
                property("color", "var(--vscode-errorForeground, #F0A0A0)")
            } else {
                property("background", "var(--vscode-inputValidation-warningBackground, #5A4A2A)")
                property("color", "var(--vscode-editorWarning-foreground, #E0C070)")
            }
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Span({ style { property("margin-right", "4px") } }) { Text(if (isError) "\u2716" else "\u26A0") }
        Text(counts.label)
    }
}

private fun problemsTooltip(problems: List<BinaryProblem>): String = buildString {
    val actionable = problems.filter { it.severity != ProblemSeverity.Info }
    actionable.take(MAX_TOOLTIP_PROBLEMS).forEachIndexed { index, problem ->
        if (index > 0) append('\n')
        append(problem.severity.label).append(": ").append(problem.message)
    }
    if (actionable.size > MAX_TOOLTIP_PROBLEMS) append("\n…and ${actionable.size - MAX_TOOLTIP_PROBLEMS} more")
    append("\n\nClick for the Problems tab")
}

/**
 * One badge's tooltip. A proposal with a problem is ⚠-marked and warning-coloured, its
 * failing runtimes come first and are coloured the same, and a remark says what that means
 * for the project. Mirrors `ProposalTooltip` in `FileDescriptionBar.kt`.
 */
private fun proposalTooltip(
    proposal: WasmProposal,
    entry: ProposalCompatibility?,
    canOpenCompatibilityTab: Boolean,
): List<ProposalTooltipLine> = buildList {
    val hasWarning = entry?.hasWarning == true
    add(ProposalTooltipLine(warningMarked("Uses ${proposal.displayName} proposal", hasWarning), warning = hasWarning))
    // Stable sort: the failing runtimes first, each group in declaration order.
    entry?.statuses?.sortedByDescending { it.support.isWarning }?.forEachIndexed { index, status ->
        val isWarning = status.support.isWarning
        add(ProposalTooltipLine(warningMarked(status.describe, isWarning), warning = isWarning, spaced = index == 0))
    }
    entry?.warningRemark?.let { add(ProposalTooltipLine(it, spaced = true)) }
    if (canOpenCompatibilityTab) add(ProposalTooltipLine("Click for the full compatibility table", spaced = true))
}

/**
 * The collapsed badge's tooltip: the proposals with a problem first, under a one-line
 * summary, each followed by its remark; then the rest. Mirrors `CollapsedProposalTooltip`.
 */
private fun collapsedProposalTooltip(
    proposals: List<WasmProposal>,
    byProposal: Map<WasmProposal, ProposalCompatibility>,
    canOpenCompatibilityTab: Boolean,
): List<ProposalTooltipLine> = buildList {
    val (warned, fine) = proposals.partition { byProposal[it]?.hasWarning == true }
    if (warned.isNotEmpty()) {
        add(ProposalTooltipLine(unsupportedProposalsSummary(warned.size, proposals.size)))
        warned.forEachIndexed { index, proposal ->
            add(ProposalTooltipLine(warningMarked(proposal.displayName, true), warning = true, spaced = index == 0))
            byProposal[proposal]?.warningRemark?.let { add(ProposalTooltipLine(it, indented = true)) }
        }
    }
    fine.forEachIndexed { index, proposal ->
        add(ProposalTooltipLine(proposal.displayName, spaced = index == 0 && warned.isNotEmpty()))
    }
    if (canOpenCompatibilityTab) add(ProposalTooltipLine("Click for the full compatibility table", spaced = true))
}

private fun warningMarked(text: String, hasWarning: Boolean): String = if (hasWarning) "\u26A0 $text" else text

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
