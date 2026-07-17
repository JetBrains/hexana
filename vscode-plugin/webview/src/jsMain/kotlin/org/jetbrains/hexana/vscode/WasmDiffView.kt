package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.FunctionWatDiff
import org.jetbrains.hexana.providers.format.ImportDelta
import org.jetbrains.hexana.providers.format.WasmEntityDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmEntityDiffResult
import org.jetbrains.hexana.providers.format.WasmFunctionWatSource
import org.jetbrains.hexana.providers.format.WasmSizeDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmWatDiffBuilder
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.EntityDiffEntry
import org.jetbrains.hexana.wasm.analysis.diff.EntityKind
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffSummary
import org.jetbrains.hexana.wasm.analysis.diff.summarize
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOp
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind

// ---------------------------------------------------------------------------
// Top-level diff view
// ---------------------------------------------------------------------------

/**
 * Top-level WASM diff view for VS Code. Mirrors the IntelliJ
 * [WasmDiffEditorView]: a tab strip over Size Impact / Entities, with a
 * bottom pane showing the side-by-side WAT of the selected function.
 */
@Composable
fun WasmDiffView(
    leftData: ByteArray,
    leftName: String,
    rightData: ByteArray,
    rightName: String,
) {
    val leftBuffer = remember(leftData) { leftData.toCommonByteBuffer() }
    val rightBuffer = remember(rightData) { rightData.toCommonByteBuffer() }

    // Compute size diff
    var sizeModel by remember { mutableStateOf<DiffLoadState<SizeReady>>(DiffLoadState.Loading) }
    LaunchedEffect(leftBuffer, rightBuffer) {
        val diff = WasmSizeDiffOrchestrator.diff(
            WasmBinaryProvider(leftBuffer.duplicate()),
            WasmBinaryProvider(rightBuffer.duplicate()),
        )
        sizeModel = if (diff == null) {
            DiffLoadState.Error("Failed to parse one or both modules.")
        } else {
            DiffLoadState.Ready(SizeReady(diff.summarize(), buildDiffRows(diff)))
        }
    }

    // Compute entity diff
    var entityModel by remember { mutableStateOf<DiffLoadState<WasmEntityDiffResult>>(DiffLoadState.Loading) }
    LaunchedEffect(leftBuffer, rightBuffer) {
        val result = WasmEntityDiffOrchestrator.diff(
            WasmBinaryProvider(leftBuffer.duplicate()),
            WasmBinaryProvider(rightBuffer.duplicate()),
        )
        entityModel = if (result == null) {
            DiffLoadState.Error("Failed to parse one or both modules.")
        } else {
            DiffLoadState.Ready(result)
        }
    }

    // WAT sources for on-demand function diff
    var watSources by remember { mutableStateOf<Pair<WasmFunctionWatSource, WasmFunctionWatSource>?>(null) }
    LaunchedEffect(leftBuffer, rightBuffer) {
        watSources = WasmFunctionWatSource(WasmBinaryProvider(leftBuffer.duplicate())) to
            WasmFunctionWatSource(WasmBinaryProvider(rightBuffer.duplicate()))
    }

    var selectedTab by remember { mutableStateOf(0) }
    var selectedFunction by remember { mutableStateOf<FunctionSelection?>(null) }

    // Compute WAT diff for selected function
    var watDiff by remember { mutableStateOf<FunctionWatDiff?>(null) }
    val src = watSources
    LaunchedEffect(src, selectedFunction) {
        watDiff = if (src == null || selectedFunction == null) {
            null
        } else {
            val (l, r) = src
            val sel = selectedFunction!!
            val li = sel.leftCodeIdx ?: l.codeIdxForName(sel.name)
            val ri = sel.rightCodeIdx ?: r.codeIdxForName(sel.name)
            WasmWatDiffBuilder.functionDiff(l, r, li, ri, sel.name)
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
            property("font-family", "var(--vscode-editor-font-family, 'Menlo', 'Monaco', 'Courier New', monospace)")
            property("font-size", "12px")
            property("color", "var(--hexana-fg, #ccc)")
            property("background", "var(--vscode-editor-background, #1e1e1e)")
        }
    }) {
        // Header
        Div({
            style {
                property("padding", "8px 12px")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
                property("font-weight", "bold")
                property("flex-shrink", "0")
            }
        }) {
            Text("$leftName  ↔  $rightName")
        }

        // Tab strip
        val tabLabels = listOf("Size Impact", "Entities")
        DiffTabBar(tabLabels, selectedTab) { selectedTab = it }

        // Top panel: table content
        Div({
            style {
                property("flex", "1")
                property("min-height", "0")
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
                property("overflow", "hidden")
                property("padding", "8px 12px")
            }
        }) {
            when (selectedTab) {
                0 -> SizeDiffContent(sizeModel, leftName, rightName) { selectedFunction = it }
                1 -> EntityDiffContent(entityModel) { selectedFunction = it }
            }
        }

        // Bottom panel: WAT diff detail
        Div({
            style {
                property("border-top", "2px solid var(--hexana-border, #333)")
                property("flex", "0 0 40%")
                property("min-height", "120px")
                property("overflow-y", "auto")
                property("padding", "8px 12px")
            }
        }) {
            WatDiffDetailPanel(selectedFunction, watDiff)
        }
    }
}

// ---------------------------------------------------------------------------
// Loading state
// ---------------------------------------------------------------------------

private sealed interface DiffLoadState<out T> {
    data object Loading : DiffLoadState<Nothing>
    data class Error(val message: String) : DiffLoadState<Nothing>
    data class Ready<T>(val value: T) : DiffLoadState<T>
}

// ---------------------------------------------------------------------------
// Tab bar
// ---------------------------------------------------------------------------

@Composable
private fun DiffTabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("flex-shrink", "0")
        }
    }) {
        for ((i, label) in labels.withIndex()) {
            val isActive = i == selected
            Div({
                style {
                    property("padding", "6px 16px")
                    property("cursor", "pointer")
                    property("user-select", "none")
                    property("border-bottom", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                    property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                }
                onClick { onSelect(i) }
            }) {
                Text(label)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Size diff
// ---------------------------------------------------------------------------

private class SizeReady(val summary: SizeDiffSummary, val rows: List<DiffRow>)

private data class DiffRow(
    val section: String,
    val name: String,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val delta: Int,
    val confidence: Float?,
    val kind: NodeKind,
    val key: String,
) {
    fun changeLabel(): String {
        val base = classification.label()
        val conf = confidence?.takeIf { it < 1f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        return base + conf
    }

    fun toSelection(): FunctionSelection? {
        if (kind != NodeKind.FUNCTION) return null
        return when {
            key.startsWith("fn:pair:") -> {
                val parts = key.removePrefix("fn:pair:").split(":")
                FunctionSelection(name, parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
            }
            key.startsWith("fn:rmL:") -> FunctionSelection(name, key.removePrefix("fn:rmL:").toIntOrNull(), null)
            key.startsWith("fn:addR:") -> FunctionSelection(name, null, key.removePrefix("fn:addR:").toIntOrNull())
            key.startsWith("fn:idx:") -> {
                val n = key.removePrefix("fn:idx:").toIntOrNull()
                FunctionSelection(name, n, n)
            }
            else -> FunctionSelection(name, null, null)
        }
    }
}

private data class FunctionSelection(val name: String, val leftCodeIdx: Int?, val rightCodeIdx: Int?)

private fun buildDiffRows(root: SizeDiffNode): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    for (section in root.children) {
        val sectionName = section.displayName
        val leaves = section.flatten().filter {
            it.kind == NodeKind.FUNCTION || it.kind == NodeKind.DATA_SEGMENT
        }
        if (leaves.isNotEmpty()) {
            leaves.forEach { rows.add(it.toRow(sectionName)) }
        } else {
            rows.add(section.toRow(sectionName))
        }
    }
    return rows
}

private fun SizeDiffNode.toRow(section: String): DiffRow =
    DiffRow(
        section = section,
        name = displayName,
        classification = classification,
        sizeA = sizeA,
        sizeB = sizeB,
        delta = delta,
        confidence = confidence,
        kind = kind,
        key = key.value,
    )

@Composable
private fun SizeDiffContent(
    model: DiffLoadState<SizeReady>,
    leftName: String,
    rightName: String,
    onSelectFunction: (FunctionSelection?) -> Unit,
) {
    when (model) {
        DiffLoadState.Loading -> Div { Text("Comparing modules…") }
        is DiffLoadState.Error -> Div { Text(model.message) }
        is DiffLoadState.Ready -> {
            val ready = model.value
            val changed = ready.rows.filter { it.classification != DiffClass.IDENTICAL }
            val hidden = ready.rows.size - changed.size

            SizeSummaryHeader(ready.summary, leftName, rightName)
            DiffDivider()

            if (changed.isEmpty()) {
                Div({ style { property("padding", "8px 0") } }) {
                    Text("Modules are identical — no size changes.")
                }
                return
            }

            SearchableTable(
                headers = listOf("Section", "Name", "Change", "Base", "Head", "Δ"),
                defaultSortColumn = 5,
                defaultSortDirection = SortDirection.DESC,
                alwaysShowSearch = true,
            ) {
                for (r in changed) {
                    PanelTableRow(
                        backgroundColor = diffColor(r.classification),
                        onClick = { onSelectFunction(r.toSelection()) },
                    ) {
                        Td({ classes("cell") }) { Text(r.section) }
                        Td({ classes("cell") }) { Text(r.name) }
                        Td({ classes("cell") }) { Text(r.changeLabel()) }
                        Td({ classes("cell-right") }) { Text(r.sizeA?.let { formatSize(it) } ?: "—") }
                        Td({ classes("cell-right") }) { Text(r.sizeB?.let { formatSize(it) } ?: "—") }
                        Td({ classes("cell-right") }) { Text(signedSize(r.delta)) }
                    }
                }
            }

            Div({ style { property("padding", "4px 0"); property("opacity", "0.7") } }) {
                Text("$hidden identical ${if (hidden == 1) "entity" else "entities"} hidden")
            }
        }
    }
}

@Composable
private fun SizeSummaryHeader(summary: SizeDiffSummary, leftName: String, rightName: String) {
    val added = summary.sections.sumOf { it.addedFns }
    val removed = summary.sections.sumOf { it.removedFns }
    val modified = summary.sections.sumOf { it.modifiedFns }
    val moved = summary.sections.sumOf { it.movedFns }
    val sectionLine = summary.sections
        .filter { it.delta != 0 }
        .joinToString("    ") { "${it.name} ${signedSize(it.delta)}" }

    val baseTotal = summary.baseTotal
    val headTotal = summary.headTotal
    val sizeLine = if (baseTotal != null && headTotal != null) {
        "${formatSize(baseTotal)}  →  ${formatSize(headTotal)}    (${signedSize(summary.totalDelta)})"
    } else {
        "${signedSize(summary.totalDelta)} total"
    }

    Div({ style { property("padding-bottom", "6px") } }) {
        Div { Text("$leftName  →  $rightName") }
        Div({ style { property("font-weight", "bold") } }) { Text(sizeLine) }
        if (sectionLine.isNotEmpty()) {
            Div { Text(sectionLine) }
        }
        Div { Text("functions: +$added added, -$removed removed, ~$modified modified, $moved moved") }
    }
}

// ---------------------------------------------------------------------------
// Entity diff
// ---------------------------------------------------------------------------

@Composable
private fun EntityDiffContent(
    model: DiffLoadState<WasmEntityDiffResult>,
    onSelectFunction: (FunctionSelection?) -> Unit,
) {
    when (model) {
        DiffLoadState.Loading -> Div { Text("Comparing entities…") }
        is DiffLoadState.Error -> Div { Text(model.message) }
        is DiffLoadState.Ready -> {
            val result = model.value
            val changed = result.entities.entries.filter { it.classification != DiffClass.IDENTICAL }
            val hidden = result.entities.entries.size - changed.size

            NewImportsBanner(result.newImports, result.removedImports)
            DiffDivider()

            if (changed.isEmpty()) {
                Div({ style { property("padding", "8px 0") } }) {
                    Text("No entity changes — modules match at the entity level.")
                }
                return
            }

            SearchableTable(
                headers = listOf("Kind", "Name", "Change", "Detail"),
                defaultSortColumn = -1,
                alwaysShowSearch = true,
            ) {
                for (e in changed) {
                    PanelTableRow(
                        backgroundColor = diffColor(e.classification),
                        onClick = { onSelectFunction(e.toFunctionSelection()) },
                    ) {
                        Td({ classes("cell") }) { Text(e.kind.name) }
                        Td({ classes("cell") }) { Text(e.name ?: e.key.value) }
                        Td({ classes("cell") }) { Text(e.classification.label()) }
                        Td({ classes("cell") }) { Text(entityDetailString(e)) }
                    }
                }
            }

            Div({ style { property("padding", "4px 0"); property("opacity", "0.7") } }) {
                Text("$hidden identical ${if (hidden == 1) "entity" else "entities"} hidden")
            }
        }
    }
}

@Composable
private fun NewImportsBanner(newImports: List<ImportDelta>, removedImports: List<ImportDelta>) {
    if (newImports.isEmpty() && removedImports.isEmpty()) return
    val hostCount = newImports.count { it.isHost }
    Div({ style { property("padding-bottom", "6px") } }) {
        if (newImports.isNotEmpty()) {
            Div({ style { property("font-weight", "bold") } }) {
                Text("New imports: ${newImports.size} (${hostCount} from host namespaces)")
            }
            for (imp in newImports.sortedByDescending { it.isHost }) {
                val tag = if (imp.isHost) " [host]" else ""
                Div { Text("  + ${imp.module}.${imp.field}  (${imp.kind})$tag") }
            }
        }
        if (removedImports.isNotEmpty()) {
            Div({ style { property("font-weight", "bold") } }) {
                Text("Removed imports: ${removedImports.size}")
            }
            for (imp in removedImports) {
                Div { Text("  - ${imp.module}.${imp.field}  (${imp.kind})") }
            }
        }
    }
}

private fun EntityDiffEntry.toFunctionSelection(): FunctionSelection? {
    if (kind != EntityKind.FUNCTION) return null
    val v = key.value
    val displayName = name ?: v
    return when {
        v.startsWith("fn:rm:") -> FunctionSelection(displayName, v.removePrefix("fn:rm:").toIntOrNull(), null)
        v.startsWith("fn:add:") -> FunctionSelection(displayName, null, v.removePrefix("fn:add:").toIntOrNull())
        v.startsWith("fn:") && "->" in v -> {
            val parts = v.removePrefix("fn:").split("->")
            FunctionSelection(displayName, parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
        }
        else -> FunctionSelection(displayName, null, null)
    }
}

private fun entityDetailString(e: EntityDiffEntry): String = when (e.classification) {
    DiffClass.MODIFIED -> "${e.detailA.orEmpty()} → ${e.detailB.orEmpty()}"
    DiffClass.MOVED -> {
        val shift = e.indexDelta?.let { " (index ${if (it > 0) "+$it" else "$it"})" }.orEmpty()
        "moved$shift: ${e.detailB ?: e.detailA ?: ""}"
    }
    else -> e.detailB ?: e.detailA ?: ""
}

// ---------------------------------------------------------------------------
// WAT diff detail panel
// ---------------------------------------------------------------------------

@Composable
private fun WatDiffDetailPanel(selected: FunctionSelection?, diff: FunctionWatDiff?) {
    when {
        selected == null -> Div({ style { property("opacity", "0.7") } }) {
            Text("Select a function to compare its WAT.")
        }
        diff == null -> Div({ style { property("opacity", "0.7") } }) {
            Text("Rendering…")
        }
        else -> {
            Div({ style { property("font-weight", "bold"); property("padding-bottom", "4px") } }) {
                Text(diff.name)
            }
            if (diff.lineOps.isEmpty()) {
                Div({ style { property("opacity", "0.7") } }) {
                    Text("(no WAT — not a code-section function)")
                }
                return
            }
            val rows = remember(diff) { pairRows(diff.lineOps) }
            Div({
                style {
                    property("overflow-y", "auto")
                    property("flex", "1")
                    property("min-height", "0")
                }
            }) {
                for (row in rows) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            width(100.percent)
                        }
                    }) {
                        WatDiffCell(
                            gutter = if (row.leftRemoved) "-" else " ",
                            text = row.left,
                            isHighlighted = row.leftRemoved,
                            color = "var(--vscode-diffEditor-removedTextBackground, rgba(255,0,0,0.15))",
                        )
                        WatDiffCell(
                            gutter = if (row.rightAdded) "+" else " ",
                            text = row.right,
                            isHighlighted = row.rightAdded,
                            color = "var(--vscode-diffEditor-insertedTextBackground, rgba(0,255,0,0.15))",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatDiffCell(gutter: String, text: String?, isHighlighted: Boolean, color: String) {
    Div({
        style {
            property("flex", "1")
            property("min-width", "0")
            property("overflow-x", "auto")
            property("white-space", "pre")
            property("min-height", "1.4em")
            property("padding-right", "8px")
            if (isHighlighted) {
                property("background", color)
            }
        }
    }) {
        Text(if (text == null) "" else "$gutter $text")
    }
}

private data class DiffPairRow(
    val left: String?,
    val leftRemoved: Boolean,
    val right: String?,
    val rightAdded: Boolean,
)

private fun pairRows(ops: List<LineOp>): List<DiffPairRow> {
    val out = ArrayList<DiffPairRow>(ops.size)
    val removed = ArrayList<String>()
    val added = ArrayList<String>()
    fun flushHunk() {
        for (i in 0 until maxOf(removed.size, added.size)) {
            val l = removed.getOrNull(i)
            val r = added.getOrNull(i)
            out.add(DiffPairRow(l, l != null, r, r != null))
        }
        removed.clear()
        added.clear()
    }
    for (op in ops) {
        when (op.kind) {
            LineOpKind.REMOVED -> removed.add(op.text)
            LineOpKind.ADDED -> added.add(op.text)
            LineOpKind.SAME -> {
                flushHunk()
                out.add(DiffPairRow(op.text, false, op.text, false))
            }
        }
    }
    flushHunk()
    return out
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun DiffDivider() {
    Div({
        style {
            height(1.px)
            property("background", "var(--hexana-border, #333)")
            property("margin", "4px 0")
        }
    })
}

private fun DiffClass.label(): String = when (this) {
    DiffClass.ADDED -> "Added"
    DiffClass.REMOVED -> "Removed"
    DiffClass.MODIFIED -> "Modified"
    DiffClass.MOVED -> "Moved"
    DiffClass.IDENTICAL -> "Identical"
}

private fun signedSize(delta: Int): String = when {
    delta > 0 -> "+${formatSize(delta)}"
    delta < 0 -> "-${formatSize(-delta)}"
    else -> "0 B"
}

private fun diffColor(classification: DiffClass): String? = when (classification) {
    DiffClass.ADDED -> "var(--vscode-diffEditor-insertedTextBackground, rgba(0,255,0,0.1))"
    DiffClass.REMOVED -> "var(--vscode-diffEditor-removedTextBackground, rgba(255,0,0,0.1))"
    DiffClass.MODIFIED -> "rgba(255,165,0,0.1)"
    DiffClass.MOVED -> "rgba(100,149,237,0.1)"
    DiffClass.IDENTICAL -> null
}
