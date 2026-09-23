package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.DispatchSignatureDelta
import org.jetbrains.hexana.providers.format.ImportDelta
import org.jetbrains.hexana.providers.format.WasmDispatchSurfaceDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmEntityDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmFunctionWatSource
import org.jetbrains.hexana.providers.format.WasmSizeDiffOrchestrator
import org.jetbrains.hexana.providers.format.WasmWatDiffBuilder
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.EntityDiffEntry
import org.jetbrains.hexana.wasm.analysis.diff.EntityKind
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffSummary
import org.jetbrains.hexana.wasm.analysis.diff.summarize

internal const val WASM_DIFF_PARSE_ERROR = "Could not analyze one or both modules."

/**
 * Diff of two core Wasm modules: Size Impact, Entities and Dispatch tabs, with the
 * selected function's WAT side by side in the detail pane.
 */
class WasmModuleDiffProvider : BinaryDiffProvider {
    override fun accepts(input: BinaryDiffInput): Boolean =
        input.leftType == BinaryProviderType.Wasm && input.rightType == BinaryProviderType.Wasm

    override fun session(input: BinaryDiffInput): BinaryDiffSession = BinaryDiffSession(
        tabs = listOf(
            DiffTab("Size Impact", "Comparing modules…") { ctx -> sizeTab(input, ctx) },
            DiffTab("Entities", "Comparing entities…") { ctx -> entityTab(input, ctx) },
            DiffTab("Dispatch", "Comparing dispatch surfaces…") { ctx -> dispatchTab(input, ctx) },
        ),
        banner = { minifiedBanner(input) },
        detail = WasmWatDiffDetailSource(input),
    )
}

private fun BinaryDiffInput.leftProvider() = WasmBinaryProvider(left.duplicate())
private fun BinaryDiffInput.rightProvider() = WasmBinaryProvider(right.duplicate())

// ---------------------------------------------------------------------------
// Banner
// ---------------------------------------------------------------------------

private fun minifiedBanner(input: BinaryDiffInput): List<DiffTextLine> {
    val left = input.leftProvider().minification
    val right = input.rightProvider().minification
    val notes = buildList {
        if (left.minified) add("base: ${left.evidence}")
        if (right.minified) add("head: ${right.evidence}")
    }
    if (notes.isEmpty()) return emptyList()
    val mapsLoaded = input.leftSymbolMap != null || input.rightSymbolMap != null
    val headline = if (mapsLoaded) {
        "Public symbols are minified — symbol map loaded; original names restored for matching."
    } else {
        "Public symbols are minified — function matches are structural; treat confidence accordingly."
    }
    return listOf(DiffTextLine(headline, bold = true), DiffTextLine(notes.joinToString("    ")))
}

// ---------------------------------------------------------------------------
// Size Impact
// ---------------------------------------------------------------------------

/** One flattened, displayable size-diff entry. */
internal data class WasmSizeRow(
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
    /** Classification label with the matcher confidence appended when it is below 1.0. */
    fun changeLabel(): String {
        val conf = confidence?.takeIf { it < 1f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        return classification.label() + conf
    }
}

/**
 * Flattens the diff tree into displayable rows. Sections that decompose into
 * functions/data segments contribute one row per child; sections that don't
 * (imports, exports, custom) contribute a single section-level row. Includes
 * identical rows so the caller can show a hidden-count footer.
 */
internal fun wasmSizeRows(root: SizeDiffNode): List<WasmSizeRow> {
    val rows = ArrayList<WasmSizeRow>()
    for (section in root.children) {
        val sectionName = section.displayName
        val leaves = section.flatten().filter {
            it.kind == NodeKind.FUNCTION || it.kind == NodeKind.DATA_SEGMENT
        }
        if (leaves.isNotEmpty()) {
            leaves.forEach { rows.add(it.toWasmSizeRow(sectionName)) }
        } else {
            rows.add(section.toWasmSizeRow(sectionName))
        }
    }
    return rows
}

private fun SizeDiffNode.toWasmSizeRow(section: String) = WasmSizeRow(
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

/**
 * Function rows map to a WAT-comparison ref; other kinds select nothing. Keys mirror
 * `WasmSizeDiffOrchestrator`: semantic pairing `fn:pair:<L>:<R>`, one-sided `fn:rmL:<L>` /
 * `fn:addR:<R>`; `fn:idx:`/`fn:name:` only when the matcher could not run.
 */
internal fun sizeRowRef(kind: NodeKind, key: String, name: String): DiffItemRef? {
    if (kind != NodeKind.FUNCTION) return null
    return when {
        key.startsWith("fn:pair:") -> {
            val parts = key.removePrefix("fn:pair:").split(":")
            DiffItemRef(name, parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
        }
        key.startsWith("fn:rmL:") -> DiffItemRef(name, key.removePrefix("fn:rmL:").toIntOrNull(), null)
        key.startsWith("fn:addR:") -> DiffItemRef(name, null, key.removePrefix("fn:addR:").toIntOrNull())
        key.startsWith("fn:idx:") -> {
            val n = key.removePrefix("fn:idx:").toIntOrNull()
            DiffItemRef(name, n, n)
        }
        else -> DiffItemRef(name, null, null) // fn:name: — detail resolves by name per side
    }
}

private fun sizeTab(input: BinaryDiffInput, ctx: DiffTabContext): DiffTabContent {
    val diff = WasmSizeDiffOrchestrator.diff(
        input.leftProvider(), input.rightProvider(), input.leftSymbolMap, input.rightSymbolMap,
    ) ?: return DiffTabContent(emptyMessage = WASM_DIFF_PARSE_ERROR)
    val rows = wasmSizeRows(diff)
    val changed = rows.filter { it.classification != DiffClass.IDENTICAL }
    val header = sizeHeader(diff.summarize(), input.leftName, input.rightName)
    if (changed.isEmpty()) {
        return DiffTabContent(header, emptyMessage = "Modules are identical — no size changes.")
    }
    val table = TableTabDescriptor(
        name = "Size Impact",
        columns = listOf(
            ColumnDescriptor("Section", ColumnWidth.Measured("custom_seg_")) { i -> CellValue(changed[i].section) },
            ColumnDescriptor("Name", ColumnWidth.Measured("w".repeat(44))) { i -> CellValue(changed[i].name) },
            ColumnDescriptor("Change", ColumnWidth.Measured("Modified 100%_")) { i -> CellValue(changed[i].changeLabel()) },
            sizeColumn("Base") { changed[it].sizeA },
            sizeColumn("Head") { changed[it].sizeB },
            deltaColumn { changed[it].delta },
        ),
        rowCount = changed.size,
        defaultSortColumn = 5,
        defaultSortAscending = false,
        alwaysShowSearch = true,
        rowClickAction = { i -> changed[i].let { ctx.select(sizeRowRef(it.kind, it.key, it.name)) } },
        rowTintProvider = { i -> changed[i].classification.toRowTint() },
    )
    return DiffTabContent(
        header = header,
        table = table,
        footer = hiddenFooter(rows.size - changed.size, "entity", "entities"),
    )
}

private fun sizeHeader(summary: SizeDiffSummary, leftName: String, rightName: String): List<DiffTextLine> {
    val added = summary.sections.sumOf { it.addedFns }
    val removed = summary.sections.sumOf { it.removedFns }
    val modified = summary.sections.sumOf { it.modifiedFns }
    val moved = summary.sections.sumOf { it.movedFns }
    val sectionLine = summary.sections
        .filter { it.delta != 0 }
        .joinToString("    ") { "${it.name} ${signedSize(it.delta)}" }
    return buildList {
        add(DiffTextLine("$leftName  →  $rightName"))
        add(DiffTextLine(totalsLine(summary.baseTotal, summary.headTotal, summary.totalDelta), bold = true))
        if (sectionLine.isNotEmpty()) add(DiffTextLine(sectionLine))
        add(DiffTextLine("functions: +$added added, -$removed removed, ~$modified modified, $moved moved"))
    }
}

/**
 * Absolute sizes when both are known (base → head) with the total Δ; otherwise just
 * the Δ. `headTotal - baseTotal == totalDelta`, so the three reconcile.
 */
internal fun totalsLine(baseTotal: Int?, headTotal: Int?, totalDelta: Int): String =
    if (baseTotal != null && headTotal != null) {
        "${formatSize(baseTotal)}  →  ${formatSize(headTotal)}    (${signedSize(totalDelta)})"
    } else {
        "${signedSize(totalDelta)} total"
    }

internal fun sizeColumn(name: String, size: (Int) -> Int?) =
    ColumnDescriptor(name, ColumnWidth.Measured("-9999.9 KB_")) { i ->
        val v = size(i)
        CellValue(v ?: -1, v?.let { formatSize(it) } ?: "—")
    }

internal fun deltaColumn(delta: (Int) -> Int) =
    ColumnDescriptor("Δ", ColumnWidth.Fill()) { i -> CellValue(delta(i), signedSize(delta(i))) }

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

/** Function entries carry their code-index pair in the key (`fn:L->R` / `fn:rm:L` / `fn:add:R`). */
internal fun entityRef(entry: EntityDiffEntry): DiffItemRef? {
    if (entry.kind != EntityKind.FUNCTION) return null
    val v = entry.key.value
    val displayName = entry.name ?: v
    return when {
        v.startsWith("fn:rm:") -> DiffItemRef(displayName, v.removePrefix("fn:rm:").toIntOrNull(), null)
        v.startsWith("fn:add:") -> DiffItemRef(displayName, null, v.removePrefix("fn:add:").toIntOrNull())
        v.startsWith("fn:") && "->" in v -> {
            val parts = v.removePrefix("fn:").split("->")
            DiffItemRef(displayName, parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
        }
        else -> DiffItemRef(displayName, null, null)
    }
}

internal fun entityDetail(e: EntityDiffEntry): String = when (e.classification) {
    DiffClass.MODIFIED -> "${e.detailA.orEmpty()} → ${e.detailB.orEmpty()}"
    DiffClass.MOVED -> {
        val shift = e.indexDelta?.let { " (index ${if (it > 0) "+$it" else "$it"})" }.orEmpty()
        "moved$shift: ${e.detailB ?: e.detailA ?: ""}"
    }
    else -> e.detailB ?: e.detailA ?: ""
}

private fun entityTab(input: BinaryDiffInput, ctx: DiffTabContext): DiffTabContent {
    val result = WasmEntityDiffOrchestrator.diff(
        input.leftProvider(), input.rightProvider(), input.leftSymbolMap, input.rightSymbolMap,
    ) ?: return DiffTabContent(emptyMessage = WASM_DIFF_PARSE_ERROR)
    val all = result.entities.entries
    val changed = all.filter { it.classification != DiffClass.IDENTICAL }
    val header = importsHeader(result.newImports, result.removedImports)
    if (changed.isEmpty()) {
        return DiffTabContent(header, emptyMessage = "No entity changes — modules match at the entity level.")
    }
    val table = TableTabDescriptor(
        name = "Entities",
        columns = listOf(
            ColumnDescriptor("Kind", ColumnWidth.Measured("ELEMENT__")) { i -> CellValue(changed[i].kind.name) },
            ColumnDescriptor("Name", ColumnWidth.Measured("w".repeat(40))) { i ->
                CellValue(changed[i].name ?: changed[i].key.value)
            },
            ColumnDescriptor("Change", ColumnWidth.Measured("Modified__")) { i -> CellValue(changed[i].classification.label()) },
            ColumnDescriptor("Detail", ColumnWidth.Fill()) { i -> CellValue(entityDetail(changed[i])) },
        ),
        rowCount = changed.size,
        defaultSortColumn = 0,
        defaultSortAscending = true,
        alwaysShowSearch = true,
        rowClickAction = { i -> ctx.select(entityRef(changed[i])) },
        rowTintProvider = { i -> changed[i].classification.toRowTint() },
    )
    return DiffTabContent(
        header = header,
        table = table,
        footer = hiddenFooter(all.size - changed.size, "entity", "entities"),
    )
}

/** The supply-chain signal: imports that entered or left, host namespaces first. */
private fun importsHeader(newImports: List<ImportDelta>, removedImports: List<ImportDelta>): List<DiffTextLine> =
    buildList {
        if (newImports.isNotEmpty()) {
            val hostCount = newImports.count { it.isHost }
            add(DiffTextLine("New imports: ${newImports.size} ($hostCount from host namespaces)", bold = true))
            newImports.sortedByDescending { it.isHost }.forEach { imp ->
                val tag = if (imp.isHost) " [host]" else ""
                add(DiffTextLine("  + ${imp.module}.${imp.field}  (${imp.kind})$tag"))
            }
        }
        if (removedImports.isNotEmpty()) {
            add(DiffTextLine("Removed imports: ${removedImports.size}", bold = true))
            removedImports.forEach { imp -> add(DiffTextLine("  - ${imp.module}.${imp.field}  (${imp.kind})")) }
        }
    }

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

/** One table row: a single candidate that entered or left one signature's `call_indirect` table. */
private class DispatchRow(
    val delta: DispatchSignatureDelta,
    val name: String,
    val entered: Boolean,
    val functionAddedOrRemoved: Boolean,
    val leftCodeIdx: Int?,
    val rightCodeIdx: Int?,
) {
    val functionLabel: String
        get() = when {
            entered && functionAddedOrRemoved -> "new"
            !entered && functionAddedOrRemoved -> "removed"
            else -> "existing"
        }
}

/**
 * Per canonical `call_indirect` signature, which statically known candidates entered or
 * left the table between the two builds. Candidate identity is the content-hash function
 * matcher: an entered "existing" function was already present in the baseline but newly
 * added to the table — the quiet mechanism that pins code wasm-opt cannot remove.
 */
private fun dispatchTab(input: BinaryDiffInput, ctx: DiffTabContext): DiffTabContent {
    val result = WasmDispatchSurfaceDiffOrchestrator.diff(
        input.leftProvider(), input.rightProvider(), input.leftSymbolMap, input.rightSymbolMap,
    ) ?: return DiffTabContent(emptyMessage = WASM_DIFF_PARSE_ERROR)
    val rows = result.deltas.flatMap { delta ->
        delta.entered.map { DispatchRow(delta, it.name, true, it.functionAddedOrRemoved, it.leftCodeIdx, it.rightCodeIdx) } +
            delta.left.map { DispatchRow(delta, it.name, false, it.functionAddedOrRemoved, it.leftCodeIdx, it.rightCodeIdx) }
    }
    if (rows.isEmpty()) {
        return DiffTabContent(emptyMessage = "No dispatch-surface changes — every signature keeps its candidate set.")
    }
    val table = TableTabDescriptor(
        name = "Dispatch",
        columns = listOf(
            ColumnDescriptor("Signature", ColumnWidth.Measured("w".repeat(36))) { i -> CellValue(rows[i].delta.signatureText) },
            ColumnDescriptor("Candidates", ColumnWidth.Measured("Candidates____")) { i ->
                CellValue("${rows[i].delta.baselineCount} → ${rows[i].delta.currentCount}")
            },
            ColumnDescriptor("Change", ColumnWidth.Measured("Entered__")) { i ->
                CellValue(if (rows[i].entered) "Entered" else "Left")
            },
            ColumnDescriptor("Function", ColumnWidth.Measured("existing____")) { i -> CellValue(rows[i].functionLabel) },
            ColumnDescriptor("Candidate", ColumnWidth.Fill()) { i -> CellValue(rows[i].name) },
        ),
        rowCount = rows.size,
        defaultSortColumn = 0,
        defaultSortAscending = true,
        alwaysShowSearch = true,
        rowClickAction = { i -> rows[i].let { ctx.select(DiffItemRef(it.name, it.leftCodeIdx, it.rightCodeIdx)) } },
        rowTintProvider = { i -> if (rows[i].entered) RowTint.Added else RowTint.Removed },
    )
    return DiffTabContent(
        table = table,
        footer = "${result.deltas.size} signatures changed · ${result.unchangedSignatures} unchanged",
    )
}

// ---------------------------------------------------------------------------
// Detail: side-by-side WAT of the selected function
// ---------------------------------------------------------------------------

/**
 * Renders one function's WAT on each side with a line-level diff. The two render
 * sources are built once, on the first [render]; each selection then renders just
 * that function.
 */
internal class WasmWatDiffDetailSource(private val input: BinaryDiffInput) : DiffDetailSource {
    private val sources by lazy {
        WasmFunctionWatSource(input.leftProvider(), input.leftSymbolMap) to
            WasmFunctionWatSource(input.rightProvider(), input.rightSymbolMap)
    }

    override val placeholder: String get() = "Select a function to compare its WAT."

    override fun render(ref: DiffItemRef): SideBySideDiff {
        val (l, r) = sources
        val li = ref.leftIndex ?: l.codeIdxForName(ref.name)
        val ri = ref.rightIndex ?: r.codeIdxForName(ref.name)
        val diff = WasmWatDiffBuilder.functionDiff(l, r, li, ri, ref.name)
        return SideBySideDiff(
            title = diff.name,
            rows = sideBySideRows(diff.lineOps),
            emptyMessage = if (diff.lineOps.isEmpty()) "(no WAT — not a code-section function)" else null,
        )
    }
}
