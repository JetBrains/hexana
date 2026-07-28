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

    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? {
        // sizeAnalysis is expensive (full module parse); check cheaply whether the provider
        // is a type that supports it. For Wasm we know it will be available if the module parsed.
        // For other providers we can't tell cheaply, so return null to fall back to full computation.
        return when (provider) {
            is WasmBinaryProvider -> Pair(if (provider.canProvideWasmModule()) listOf("Top") else emptyList(), emptyList())
            else -> null
        }
    }
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

    val treemap = buildTreemapData(root, moduleSize, builder, wasmProvider, dataRecords, cache)

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
        treemapData = treemap,
    )
}

/**
 * Builds [TreemapData] from the size analysis tree, recursively converting
 * [BinarySizeNode]s into [TreemapNode]s with tree paths that correspond to
 * row indices in the table.
 */
private fun buildTreemapData(
    root: BinarySizeNode,
    moduleSize: Int,
    builder: SizeAnalysisBuilder?,
    provider: WasmBinaryProvider?,
    dataRecords: List<WasmDataRecord>,
    cache: MutableMap<Any, List<BinarySizeNode>>,
): TreemapData {
    fun buildNode(node: BinarySizeNode, parentPath: List<Int>, index: Int, depth: Int): TreemapNode {
        val path = parentPath + index
        val resolvedChildren = if (depth < 2) {
            resolveNodeChildren(node, builder, provider, dataRecords, cache)
        } else emptyList()
        val childNodes = resolvedChildren.mapIndexed { childIndex, child ->
            buildNode(child, path, childIndex, depth + 1)
        }
        return TreemapNode(
            name = node.name,
            size = node.size.toLong(),
            children = childNodes,
            treePath = path,
        )
    }

    val topNodes = root.children.mapIndexed { index, child ->
        buildNode(child, emptyList(), index, 0)
    }
    return TreemapData(
        root = TreemapNode(name = root.name, size = root.size.toLong(), children = topNodes),
        totalSize = moduleSize.toLong(),
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
