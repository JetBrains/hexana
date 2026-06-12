package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.line.LineDiff
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOp
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind

/**
 * Per-module WAT render source for the on-demand function diff. Builds the parse,
 * symbolic renderer, and code-chunk index **once** (the only non-trivial cost is
 * the single [buildWasmChunkTree] pass; function bodies are parsed lazily per
 * [lines] call). Hold one instance per side for the lifetime of the diff editor
 * and render individual functions on selection.
 */
public class WasmFunctionWatSource(provider: WasmBinaryProvider) {
    private val snapshot = binaryDocumentOf(provider.bytes).snapshot()
    private val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
    private val renderer = WasmHexanaWatRenderer(parsed).apply { symbolicOperands = true }
    private val resolver = WasmSymbolResolver(provider)
    private val importCount = provider.importFuncCount

    private val codeChunkByIndex: Map<Int, BinaryChunk.Element> = buildMap {
        val tree = buildWasmChunkTree(parsed)
        for (i in 0 until tree.totalChunks) {
            val chunk = tree.chunkAt(i)
            if (chunk is BinaryChunk.Element && chunk.kind == WasmSectionKinds.CODE) {
                put(chunk.indexInSection, chunk)
            }
        }
    }

    private val codeIdxByName: Map<String, Int> by lazy {
        buildMap {
            for (codeIdx in codeChunkByIndex.keys) {
                resolver.resolve(SymbolKind.FUNCTION, importCount + codeIdx)?.let { put(it, codeIdx) }
            }
        }
    }

    /** Clean symbolic WAT content lines for one function (cheap — renders a single chunk). */
    public fun lines(codeIdx: Int): List<String> =
        codeChunkByIndex[codeIdx]?.let { renderer.watContentLines(snapshot, it) } ?: emptyList()

    public fun name(codeIdx: Int): String =
        resolver.resolve(SymbolKind.FUNCTION, importCount + codeIdx) ?: "\$func${importCount + codeIdx}"

    /** Code index of the function with this resolved name, or null. */
    public fun codeIdxForName(name: String): Int? = codeIdxByName[name]
}

/** Side-by-side WAT of a single function, with a line-level diff. */
public data class FunctionWatDiff(
    val name: String,
    val leftLines: List<String>,
    val rightLines: List<String>,
    val lineOps: List<LineOp>,
)

/** Builds the on-demand, single-function WAT side-by-side diff. */
public object WasmWatDiffBuilder {

    /**
     * Renders the selected function on each side and line-diffs them. A null code
     * index means the function is absent on that side (added/removed) — the line
     * ops then mark the present side entirely ADDED/REMOVED.
     */
    public fun functionDiff(
        left: WasmFunctionWatSource,
        right: WasmFunctionWatSource,
        leftCodeIdx: Int?,
        rightCodeIdx: Int?,
        name: String,
    ): FunctionWatDiff {
        val leftLines = leftCodeIdx?.let { left.lines(it) }.orEmpty()
        val rightLines = rightCodeIdx?.let { right.lines(it) }.orEmpty()
        val lineOps = when {
            leftCodeIdx != null && rightCodeIdx != null -> LineDiff.diff(leftLines, rightLines)
            rightCodeIdx != null -> rightLines.mapIndexed { i, t -> LineOp(LineOpKind.ADDED, t, null, i) }
            leftCodeIdx != null -> leftLines.mapIndexed { i, t -> LineOp(LineOpKind.REMOVED, t, i, null) }
            else -> emptyList()
        }
        return FunctionWatDiff(name, leftLines, rightLines, lineOps)
    }
}
