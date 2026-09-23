package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.wasm.analysis.diff.BinarySizeDiff
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode

/**
 * Diff of two native binaries (ELF, Mach-O, PE, COFF, archives): per-section size impact.
 *
 * Accepts any pair where neither side is Wasm, so it is the registry's fallback. It has no
 * detail pane yet; a per-function disassembly comparison plugs in as a [DiffDetailSource].
 */
class NativeDiffProvider : BinaryDiffProvider {
    override fun accepts(input: BinaryDiffInput): Boolean =
        input.leftType == BinaryProviderType.Generic && input.rightType == BinaryProviderType.Generic

    override fun session(input: BinaryDiffInput): BinaryDiffSession = BinaryDiffSession(
        tabs = listOf(DiffTab("Size Impact", "Comparing binaries…") { nativeSizeTab(input) }),
    )
}

/** The native size diff, flattened for display — computed by [nativeSizeDiff]. */
class NativeSizeDiff(
    val baseTotal: Int?,
    val headTotal: Int?,
    val totalDelta: Int,
    /** Every row, identical ones included, so callers can count what a table hides. */
    val rows: List<NativeSizeRow>,
)

/** One displayable section (or `segment/section`) of a native size diff. */
data class NativeSizeRow(
    val name: String,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val delta: Int,
)

/** Parses both sides and diffs their section sizes; `null` when either side has no size analysis. */
fun nativeSizeDiff(input: BinaryDiffInput): NativeSizeDiff? {
    val leftSize = nativeOrGenericBinaryProvider(input.left.duplicate()).sizeAnalysis ?: return null
    val rightSize = nativeOrGenericBinaryProvider(input.right.duplicate()).sizeAnalysis ?: return null
    val diff = BinarySizeDiff.diff(leftSize, rightSize)
    return NativeSizeDiff(diff.sizeA, diff.sizeB, diff.delta, nativeSizeRows(diff))
}

/**
 * Flattens the diff tree into displayable rows. Each top-level child (section/segment)
 * becomes a row; a parent with children (e.g. a Mach-O segment with sections) contributes
 * its children as `segment/section` rows followed by itself as a summary row.
 */
internal fun nativeSizeRows(root: SizeDiffNode): List<NativeSizeRow> {
    val rows = ArrayList<NativeSizeRow>()
    for (child in root.children) {
        for (sub in child.children) {
            rows.add(sub.toNativeRow(prefix = "${child.displayName}/"))
        }
        rows.add(child.toNativeRow())
    }
    return rows
}

private fun SizeDiffNode.toNativeRow(prefix: String = "") =
    NativeSizeRow(prefix + displayName, classification, sizeA, sizeB, delta)

private fun nativeSizeTab(input: BinaryDiffInput): DiffTabContent {
    val diff = nativeSizeDiff(input)
        ?: return DiffTabContent(emptyMessage = "Size analysis is not available for one or both binaries.")
    val rows = diff.rows
    val changed = rows.filter { it.classification != DiffClass.IDENTICAL }
    val header = buildList {
        add(DiffTextLine("${input.leftName}  →  ${input.rightName}"))
        add(DiffTextLine(totalsLine(diff.baseTotal, diff.headTotal, diff.totalDelta), bold = true))
        val sectionLine = rows.filter { it.delta != 0 }.joinToString("    ") { "${it.name} ${signedSize(it.delta)}" }
        if (sectionLine.isNotEmpty()) add(DiffTextLine(sectionLine))
        val added = rows.count { it.classification == DiffClass.ADDED }
        val removed = rows.count { it.classification == DiffClass.REMOVED }
        val modified = rows.count { it.classification == DiffClass.MODIFIED }
        add(DiffTextLine("sections: +$added added, -$removed removed, ~$modified modified"))
    }
    if (changed.isEmpty()) {
        return DiffTabContent(header, emptyMessage = "Binaries are identical — no size changes.")
    }
    val table = TableTabDescriptor(
        name = "Size Impact",
        columns = listOf(
            ColumnDescriptor("Name", ColumnWidth.Measured("w".repeat(32))) { i -> CellValue(changed[i].name) },
            ColumnDescriptor("Change", ColumnWidth.Measured("Modified__")) { i -> CellValue(changed[i].classification.label()) },
            sizeColumn("Base") { changed[it].sizeA },
            sizeColumn("Head") { changed[it].sizeB },
            deltaColumn { changed[it].delta },
        ),
        rowCount = changed.size,
        defaultSortColumn = 4,
        defaultSortAscending = false,
        showIdColumn = false,
        alwaysShowSearch = true,
        rowTintProvider = { i -> changed[i].classification.toRowTint() },
    )
    return DiffTabContent(
        header = header,
        table = table,
        footer = hiddenFooter(rows.size - changed.size, "section", "sections"),
    )
}
