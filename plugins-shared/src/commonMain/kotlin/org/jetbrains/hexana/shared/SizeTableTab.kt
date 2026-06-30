package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.sections.WasmDataRecord

/**
 * [TableTabsProvider] that produces the "Top" (size analysis) tab for any
 * [BinaryProvider] whose [BinaryProvider.sizeAnalysis] is non-null.
 */
class SizeTableTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        listOfNotNull(sizeTab(provider))
}

/**
 * Builds the "Top" (size analysis) [TableTabDescriptor] for any [BinaryProvider]
 * whose [BinaryProvider.sizeAnalysis] is non-null.
 *
 * The descriptor uses [TableTabDescriptor.childrenProvider] to express the size
 * tree as a nested table, matching the IntelliJ-native `PanelSize` behavior.
 */
private fun sizeTab(provider: BinaryProvider): TableTabDescriptor? {
    val root = provider.sizeAnalysis ?: return null
    val wasmProvider = provider as? WasmBinaryProvider
    val builder = wasmProvider?.sizeAnalysisBuilder
    val dataRecords: List<WasmDataRecord> = if (wasmProvider != null) wasmProvider.data else emptyList()
    val moduleSize = root.size

    // Shared cache for lazily-resolved children (function detail, data segment detail,
    // embedded module/component parsing). Keyed by a stable identity per node.
    val cache = mutableMapOf<Any, List<BinarySizeNode>>()

    val topChildren = root.children
    if (topChildren.isEmpty()) return null

    fun columnsForChildren(
        children: List<BinarySizeNode>,
        totalSize: Int,
    ): List<ColumnDescriptor> = listOf(
        ColumnDescriptor(
            name = "Type",
            widthBehavior = ColumnWidth.Fill(),
        ) { index -> CellValue(children[index].name) },
        ColumnDescriptor(
            name = "Size",
            widthBehavior = ColumnWidth.Measured("000.00 KiB"),
        ) { index -> CellValue(children[index].size, formatSize(children[index].size)) },
        ColumnDescriptor(
            name = "%",
            widthBehavior = ColumnWidth.Measured("100.0%"),
        ) { index ->
            val pct = children[index].percentage(totalSize)
            CellValue(pct, "${formatOneDecimal(pct)}%")
        },
        ColumnDescriptor(
            name = "Offset",
            widthBehavior = ColumnWidth.Measured("0x00000000"),
        ) { index ->
            val hex = "0x${children[index].offset.toString(16).uppercase()}"
            CellValue(children[index].offset, hex)
        },
    )

    fun childRowsForNode(node: BinarySizeNode): TableTabChildRows? {
        val resolved = resolveNodeChildren(node, builder, wasmProvider, dataRecords, cache)
        if (resolved.isEmpty()) return null
        val cols = columnsForChildren(resolved, moduleSize)
        return TableTabChildRows(
            columns = cols,
            rowCount = resolved.size,
            rowSelectionProvider = { index ->
                val child = resolved[index]
                if (child.size > 0) RowSelectionData(child.offset, child.size) else null
            },
            childrenProvider = { index -> childRowsForNode(resolved[index]) },
        )
    }

    val topColumns = columnsForChildren(topChildren, moduleSize)

    return TableTabDescriptor(
        name = "Top",
        columns = topColumns,
        rowCount = topChildren.size,
        defaultSortColumn = 1,
        defaultSortAscending = false,
        rowSelectionProvider = { index ->
            val node = topChildren[index]
            if (node.size > 0) RowSelectionData(node.offset, node.size) else null
        },
        childrenProvider = { index -> childRowsForNode(topChildren[index]) },
    )
}

/**
 * Resolves children for a [BinarySizeNode], handling lazy expansion of
 * functions, data segments, and embedded modules/components.
 *
 * Mirrors the `resolveChildren` logic from the IntelliJ-specific `PanelSize.kt`.
 */
private fun resolveNodeChildren(
    node: BinarySizeNode,
    builder: SizeAnalysisBuilder?,
    provider: WasmBinaryProvider?,
    dataRecords: List<WasmDataRecord>,
    cache: MutableMap<Any, List<BinarySizeNode>>,
): List<BinarySizeNode> {
    if (node.children.isNotEmpty()) return node.children

    // Embedded modules / nested components expand lazily from their own byte slice.
    when (node) {
        is SizeNode.EmbeddedComponent -> return lazyEmbeddedChildren(node.slice, node, cache)
        is SizeNode.EmbeddedModule -> return lazyEmbeddedChildren(node.slice, node, cache)
        else -> {}
    }

    if (builder == null) return emptyList()

    return when (node) {
        is SizeNode.Function -> {
            cache.getOrPut("func:${node.funcIndex}") {
                val record = provider?.parseCodeRecord(node.funcIndex) ?: return emptyList()
                builder.buildFunctionDetail(record)
            }
        }
        is SizeNode.DataSegment -> {
            cache.getOrPut("data:${node.segmentIndex}") {
                builder.buildDataSegmentDetail(dataRecords, node.segmentIndex)
            }
        }
        else -> emptyList()
    }
}

private fun lazyEmbeddedChildren(
    slice: CommonByteBuffer?,
    node: BinarySizeNode,
    cache: MutableMap<Any, List<BinarySizeNode>>,
): List<BinarySizeNode> {
    if (slice == null) return emptyList()
    return cache.getOrPut(node) {
        val sub = (WasmParser().parse(slice) as? WasmParseSuccess)?.wasmModule
        if (sub == null) emptyList() else SizeAnalysisBuilder(slice, sub).buildTree().children
    }
}
