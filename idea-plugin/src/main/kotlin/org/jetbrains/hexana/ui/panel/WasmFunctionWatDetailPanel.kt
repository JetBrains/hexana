package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.FunctionWatDiff
import org.jetbrains.hexana.providers.format.WasmFunctionWatSource
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.providers.format.WasmWatDiffBuilder
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOp
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind
import org.jetbrains.jewel.ui.component.Text

/** A function selected for WAT comparison. [leftCodeIdx]/[rightCodeIdx] are code-section indices. */
data class FunctionSelection(val name: String, val leftCodeIdx: Int?, val rightCodeIdx: Int?)

/**
 * Bottom detail pane of the WASM diff editor: renders the WAT of the one selected
 * function side-by-side (base | head) with a line-level diff. The two render
 * sources are built once per editor open; each selection renders just that
 * function (instant), so the heavy whole-module work the v3 tab did is gone.
 */
@Composable
fun WasmFunctionWatDetailPanel(
    selected: FunctionSelection?,
    leftBuffer: CommonByteBuffer,
    rightBuffer: CommonByteBuffer,
    leftSymbolMap: WasmSymbolMap? = null,
    rightSymbolMap: WasmSymbolMap? = null,
) {
    var sources by remember(leftBuffer, rightBuffer) {
        mutableStateOf<Pair<WasmFunctionWatSource, WasmFunctionWatSource>?>(null)
    }
    LaunchedEffect(leftBuffer, rightBuffer) {
        sources = withContext(Dispatchers.IO) {
            WasmFunctionWatSource(WasmBinaryProvider(leftBuffer.duplicate()), leftSymbolMap) to
                WasmFunctionWatSource(WasmBinaryProvider(rightBuffer.duplicate()), rightSymbolMap)
        }
    }

    var diff by remember { mutableStateOf<FunctionWatDiff?>(null) }
    val src = sources
    LaunchedEffect(src, selected) {
        diff = if (src == null || selected == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val (l, r) = src
                val li = selected.leftCodeIdx ?: l.codeIdxForName(selected.name)
                val ri = selected.rightCodeIdx ?: r.codeIdxForName(selected.name)
                WasmWatDiffBuilder.functionDiff(l, r, li, ri, selected.name)
            }
        }
    }

    when {
        selected == null -> Centered("Select a function to compare its WAT.")
        src == null || diff == null -> Centered("Rendering…")
        else -> FunctionDiffContent(diff!!)
    }
}

@Composable
private fun FunctionDiffContent(diff: FunctionWatDiff) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            diff.name,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        if (diff.lineOps.isEmpty()) {
            Centered("(no WAT — not a code-section function)")
            return@Column
        }
        val rows = remember(diff) { pairRows(diff.lineOps) }
        val removedBg = HexanaTheme.diffRemovedColor
        val addedBg = HexanaTheme.diffAddedColor
        val none = Color.Transparent
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows.size) { i ->
                val row = rows[i]
                val leftBg = if (row.leftRemoved) removedBg else none
                val rightBg = if (row.rightAdded) addedBg else none
                Row(modifier = Modifier.fillMaxWidth()) {
                    WatCell(if (row.leftRemoved) "-" else " ", row.left, leftBg, Modifier.weight(0.5f))
                    WatCell(if (row.rightAdded) "+" else " ", row.right, rightBg, Modifier.weight(0.5f))
                }
            }
        }
    }
}

/** A side-by-side row: base line on the left, head line on the right. */
private data class DiffPairRow(
    val left: String?,
    val leftRemoved: Boolean,
    val right: String?,
    val rightAdded: Boolean,
)

/**
 * Aligns the flat line-ops into side-by-side rows: SAME lines fill both columns
 * (same row, no color); within a changed hunk, removed and added lines are zipped
 * so a modified line shows old (red, left) | new (green, right) on one row, with
 * any surplus removed/added lines occupying a single side.
 */
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

@Composable
private fun WatCell(gutter: String, text: String?, background: Color, modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth().background(background).padding(end = 8.dp)) {
        Text(
            if (text == null) "" else "$gutter $text",
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun Centered(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}
