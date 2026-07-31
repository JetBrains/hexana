package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.diff.BinarySizeDiff
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode

// ---------------------------------------------------------------------------
// Top-level native binary diff view
// ---------------------------------------------------------------------------

/**
 * Top-level native binary diff view for VS Code. Shows per-section size
 * differences between two native binaries (ELF, Mach-O, PE, etc.).
 * No WAT view or entity diff — only size impact.
 */
@Composable
fun NativeDiffView(
    leftData: ByteArray,
    leftName: String,
    rightData: ByteArray,
    rightName: String,
) {
    val leftBuffer = remember(leftData) { leftData.toCommonByteBuffer() }
    val rightBuffer = remember(rightData) { rightData.toCommonByteBuffer() }

    var model by remember { mutableStateOf<NativeDiffLoadState>(NativeDiffLoadState.Loading) }
    LaunchedEffect(leftBuffer, rightBuffer) {
        val leftProvider = nativeOrGenericBinaryProvider(leftBuffer.duplicate())
        val rightProvider = nativeOrGenericBinaryProvider(rightBuffer.duplicate())
        val leftTree = leftProvider.sizeAnalysis
        val rightTree = rightProvider.sizeAnalysis
        model = if (leftTree == null || rightTree == null) {
            NativeDiffLoadState.Error("Failed to parse one or both binaries.")
        } else {
            val diff = BinarySizeDiff.diff(leftTree, rightTree)
            val rows = buildNativeDiffRows(diff)
            val baseTotal = diff.sizeA
            val headTotal = diff.sizeB
            val totalDelta = (headTotal ?: 0) - (baseTotal ?: 0)
            NativeDiffLoadState.Ready(NativeSizeReady(baseTotal, headTotal, totalDelta, rows))
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

        // Content
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
            NativeSizeDiffContent(model, leftName, rightName)
        }
    }
}

// ---------------------------------------------------------------------------
// Loading state
// ---------------------------------------------------------------------------

private sealed interface NativeDiffLoadState {
    data object Loading : NativeDiffLoadState
    data class Error(val message: String) : NativeDiffLoadState
    data class Ready(val value: NativeSizeReady) : NativeDiffLoadState
}

private class NativeSizeReady(
    val baseTotal: Int?,
    val headTotal: Int?,
    val totalDelta: Int,
    val rows: List<NativeDiffRow>,
)

private data class NativeDiffRow(
    val name: String,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val delta: Int,
)

// ---------------------------------------------------------------------------
// Build rows from diff tree
// ---------------------------------------------------------------------------

private fun buildNativeDiffRows(root: SizeDiffNode): List<NativeDiffRow> {
    val rows = ArrayList<NativeDiffRow>()
    for (child in root.children) {
        rows.add(
            NativeDiffRow(
                name = child.displayName,
                classification = child.classification,
                sizeA = child.sizeA,
                sizeB = child.sizeB,
                delta = child.delta,
            )
        )
    }
    return rows
}

// ---------------------------------------------------------------------------
// Size diff content
// ---------------------------------------------------------------------------

@Composable
private fun NativeSizeDiffContent(
    model: NativeDiffLoadState,
    leftName: String,
    rightName: String,
) {
    when (model) {
        NativeDiffLoadState.Loading -> Div { Text("Comparing binaries…") }
        is NativeDiffLoadState.Error -> Div { Text(model.message) }
        is NativeDiffLoadState.Ready -> {
            val ready = model.value
            val changed = ready.rows.filter { it.classification != DiffClass.IDENTICAL }
            val hidden = ready.rows.size - changed.size

            NativeSummaryHeader(ready, leftName, rightName)
            NativeDiffDivider()

            if (changed.isEmpty()) {
                Div({ style { property("padding", "8px 0") } }) {
                    Text("Binaries are identical — no size changes.")
                }
                return
            }

            SearchableTable(
                headers = listOf("Name", "Change", "Base", "Head", "Δ"),
                defaultSortColumn = 4,
                defaultSortDirection = SortDirection.DESC,
                alwaysShowSearch = true,
            ) {
                for (r in changed) {
                    PanelTableRow(
                        backgroundColor = nativeDiffColor(r.classification),
                    ) {
                        Td({ classes("cell") }) { Text(r.name) }
                        Td({ classes("cell") }) { Text(r.classification.nativeLabel()) }
                        Td({ classes("cell-right") }) { Text(r.sizeA?.let { formatSize(it) } ?: "—") }
                        Td({ classes("cell-right") }) { Text(r.sizeB?.let { formatSize(it) } ?: "—") }
                        Td({ classes("cell-right") }) { Text(nativeSignedSize(r.delta)) }
                    }
                }
            }

            Div({ style { property("padding", "4px 0"); property("opacity", "0.7") } }) {
                Text("$hidden identical ${if (hidden == 1) "section" else "sections"} hidden")
            }
        }
    }
}

@Composable
private fun NativeSummaryHeader(ready: NativeSizeReady, leftName: String, rightName: String) {
    val baseTotal = ready.baseTotal
    val headTotal = ready.headTotal
    val sizeLine = if (baseTotal != null && headTotal != null) {
        "${formatSize(baseTotal)}  →  ${formatSize(headTotal)}    (${nativeSignedSize(ready.totalDelta)})"
    } else {
        "${nativeSignedSize(ready.totalDelta)} total"
    }

    val added = ready.rows.count { it.classification == DiffClass.ADDED }
    val removed = ready.rows.count { it.classification == DiffClass.REMOVED }
    val modified = ready.rows.count { it.classification == DiffClass.MODIFIED }

    Div({ style { property("padding-bottom", "6px") } }) {
        Div { Text("$leftName  →  $rightName") }
        Div({ style { property("font-weight", "bold") } }) { Text(sizeLine) }
        Div { Text("sections: +$added added, -$removed removed, ~$modified modified") }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun NativeDiffDivider() {
    Div({
        style {
            height(1.px)
            property("background", "var(--hexana-border, #333)")
            property("margin", "4px 0")
        }
    })
}

private fun DiffClass.nativeLabel(): String = when (this) {
    DiffClass.ADDED -> "Added"
    DiffClass.REMOVED -> "Removed"
    DiffClass.MODIFIED -> "Modified"
    DiffClass.MOVED -> "Moved"
    DiffClass.IDENTICAL -> "Identical"
}

private fun nativeSignedSize(delta: Int): String = when {
    delta > 0 -> "+${formatSize(delta)}"
    delta < 0 -> "-${formatSize(-delta)}"
    else -> "0 B"
}

private fun nativeDiffColor(classification: DiffClass): String? = when (classification) {
    DiffClass.ADDED -> "var(--vscode-diffEditor-insertedTextBackground, rgba(0,255,0,0.1))"
    DiffClass.REMOVED -> "var(--vscode-diffEditor-removedTextBackground, rgba(255,0,0,0.1))"
    DiffClass.MODIFIED -> "rgba(255,165,0,0.1)"
    DiffClass.MOVED -> "rgba(100,149,237,0.1)"
    DiffClass.IDENTICAL -> null
}
