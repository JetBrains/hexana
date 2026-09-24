package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.format.SubmoduleKind
import org.jetbrains.hexana.providers.format.SubmodulePairing
import org.jetbrains.hexana.providers.format.SubmoduleRef
import org.jetbrains.hexana.providers.format.SubmoduleStatus
import org.jetbrains.hexana.providers.format.WasmComponentDiffOrchestrator
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.formatSize

/**
 * Diff of two Component Model binaries at submodule granularity: a table of the embedded
 * core modules and nested component, each flagged Identical / Modified / Added / Removed.
 *
 * The expensive per-submodule comparison is deferred to the click: a row asks the host to
 * open the pair as its own diff ([DiffOpenRequest.Diff], which routes back through the
 * registry — a core-module pair to [WasmModuleDiffProvider], a nested component back here),
 * or a one-sided submodule as a single binary ([DiffOpenRequest.Single]).
 */
class WasmComponentDiffProvider : BinaryDiffProvider {
    override fun accepts(input: BinaryDiffInput): Boolean =
        input.leftType == BinaryProviderType.WasmComponent && input.rightType == BinaryProviderType.WasmComponent

    override fun session(input: BinaryDiffInput, host: BinaryDiffHost): BinaryDiffSession = BinaryDiffSession(
        tabs = listOf(DiffTab("Submodules", "Comparing components…") { ctx -> submodulesTab(input, ctx) }),
    )
}

private fun submodulesTab(input: BinaryDiffInput, ctx: DiffTabContext): DiffTabContent {
    val title = DiffTextLine("${input.leftName}  ↔  ${input.rightName}")
    val result = WasmComponentDiffOrchestrator.diff(
        WasmComponentBinaryProvider(input.left.duplicate()),
        WasmComponentBinaryProvider(input.right.duplicate()),
    ) ?: return DiffTabContent(listOf(title), emptyMessage = WASM_DIFF_PARSE_ERROR)
    val pairings = result.pairings
    if (pairings.isEmpty()) {
        return DiffTabContent(listOf(title), emptyMessage = "No embedded submodules to compare.")
    }
    val open = ctx.open
    val table = TableTabDescriptor(
        name = "Submodules",
        columns = listOf(
            ColumnDescriptor("Submodule", ColumnWidth.Measured("component[000]_")) { i -> CellValue(pairings[i].label) },
            ColumnDescriptor("Kind", ColumnWidth.Measured("component_")) { i -> CellValue(kindLabel(pairings[i].kind)) },
            ColumnDescriptor("Base", ColumnWidth.Measured("-9999.9 KB_")) { i -> sizeCell(pairings[i].left) },
            ColumnDescriptor("Head", ColumnWidth.Measured("-9999.9 KB_")) { i -> sizeCell(pairings[i].right) },
            ColumnDescriptor("Status", ColumnWidth.Fill()) { i -> CellValue(statusLabel(pairings[i].status)) },
        ),
        rowCount = pairings.size,
        defaultSortColumn = 4,
        defaultSortAscending = true,
        rowClickAction = { i -> open?.invoke(submoduleOpenRequest(input, pairings[i]) ?: return@TableTabDescriptor) },
        rowTintProvider = { i -> statusTint(pairings[i].status) },
    )
    return DiffTabContent(
        header = listOf(title, DiffTextLine(submoduleSummary(pairings.size, result.differingCount), bold = true)),
        table = table,
        footer = if (open != null) "Click a submodule to compare it in a separate tab." else null,
    )
}

/**
 * What clicking [pairing] opens. The two byte ranges are sliced from the parent buffers and
 * *materialized*, so the opened tab's lifetime is independent of the parent diff — lazily,
 * when the host invokes the request's factory off the UI thread.
 */
internal fun submoduleOpenRequest(parent: BinaryDiffInput, pairing: SubmodulePairing): DiffOpenRequest? {
    val left = pairing.left
    val right = pairing.right
    return when {
        left != null && right != null -> {
            val leftName = "${parent.leftName}∷${left.label}"
            val rightName = "${parent.rightName}∷${right.label}"
            DiffOpenRequest.Diff("$leftName ↔ $rightName") {
                BinaryDiffInput(
                    left = materialize(parent.left, left),
                    right = materialize(parent.right, right),
                    leftName = leftName,
                    rightName = rightName,
                    leftSymbolMap = parent.leftSymbolMap,
                    rightSymbolMap = parent.rightSymbolMap,
                )
            }
        }
        left != null -> DiffOpenRequest.Single("${parent.leftName}∷${left.label}") { materialize(parent.left, left) }
        right != null -> DiffOpenRequest.Single("${parent.rightName}∷${right.label}") { materialize(parent.right, right) }
        else -> null
    }
}

private fun materialize(parent: CommonByteBuffer, ref: SubmoduleRef): CommonByteBuffer =
    parent.slice(ref.contentOffset, ref.contentSize).toByteArray().toCommonByteBuffer()

/** Both sides of a pair share a label; for a one-sided row, use whichever side is present. */
private val SubmodulePairing.label: String get() = (left ?: right)?.label ?: ""

private fun sizeCell(ref: SubmoduleRef?): CellValue<Int> {
    val size = ref?.contentSize
    return CellValue(size ?: -1, size?.let { formatSize(it) } ?: "—")
}

private fun kindLabel(kind: SubmoduleKind): String = when (kind) {
    SubmoduleKind.CORE_MODULE -> "module"
    SubmoduleKind.COMPONENT -> "component"
}

private fun statusLabel(status: SubmoduleStatus): String = when (status) {
    SubmoduleStatus.IDENTICAL -> "Identical"
    SubmoduleStatus.MODIFIED -> "Modified"
    SubmoduleStatus.ADDED -> "Added"
    SubmoduleStatus.REMOVED -> "Removed"
}

private fun statusTint(status: SubmoduleStatus): RowTint? = when (status) {
    SubmoduleStatus.ADDED -> RowTint.Added
    SubmoduleStatus.REMOVED -> RowTint.Removed
    SubmoduleStatus.MODIFIED -> RowTint.Modified
    SubmoduleStatus.IDENTICAL -> null
}

private fun submoduleSummary(total: Int, differing: Int): String {
    val unit = if (total == 1) "submodule" else "submodules"
    return if (differing == 0) "$total $unit — all identical" else "$total $unit, $differing differing"
}
