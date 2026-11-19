package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode

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
            // For a .NET assembly the size tree decodes metadata and ReadyToRun tables; a PE with
            // sections always has one, and the section table is read with the headers.
            is PeBinaryProvider -> Pair(if (provider.sections?.isNotEmpty() == true) listOf("Top") else emptyList(), emptyList())
            else -> null
        }
    }
}

/** Treemap levels drawn below every Top row regardless of its size. */
internal const val TREEMAP_BASE_DEPTH: Int = 2

/**
 * A treemap node at least this share of the whole file is broken down further than
 * [TREEMAP_BASE_DEPTH], so a large nested module/component does not end up as one block.
 */
internal const val TREEMAP_DEEP_SIZE_FRACTION: Double = 0.01

/** Hard cap on treemap nesting, for pathologically deep component nesting. */
internal const val TREEMAP_MAX_DEPTH: Int = 8

/**
 * Which binary a [BinarySizeNode] was built from. Nodes of an embedded module or nested
 * component are built from that container's own byte slice, so their offsets are relative to
 * [base] and their per-function / per-data-segment detail needs that container's [wasm]
 * provider, not the outer file's.
 */
private class SizeScope(val base: Int, val wasm: WasmBinaryProvider?) {
    val builder: SizeAnalysisBuilder? by lazy { wasm?.sizeAnalysisBuilder }
    val dataRecords by lazy { wasm?.data ?: emptyList() }
}

/** One row of the Top tab: a size node, its display name, and the scope it was built in. */
private class SizeRow(val name: String, val node: BinarySizeNode, val scope: SizeScope) {
    val size: Int get() = node.size

    /** Absolute file offset (nested nodes are rebased from their container's slice). */
    val offset: Int get() = scope.base + node.offset

    /** Resolved once and shared by the table and the treemap so their tree paths agree. */
    val children: List<SizeRow> by lazy { resolveChildren(this) }
}

/**
 * Builds the "Top" (size analysis) [TableTabDescriptor] for any [BinaryProvider]
 * whose [BinaryProvider.sizeAnalysis] is non-null.
 *
 * The descriptor uses [TableTabDescriptor.childrenProvider] to express the size
 * tree as a nested table. Embedded modules and nested components of a component are rows
 * that expand into their own breakdown (with absolute offsets); the treemap draws large ones
 * deeper (see [TREEMAP_DEEP_SIZE_FRACTION]).
 */
private fun sizeTab(provider: BinaryProvider): TableTabDescriptor? {
    val root = provider.sizeAnalysis ?: return null
    return sizeTreeTab("Top", root, null, provider as? WasmBinaryProvider)
}

internal fun sizeTreeTab(name: String, root: BinarySizeNode, prologueText: String? = null): TableTabDescriptor? =
    sizeTreeTab(name, root, prologueText, null)

private fun sizeTreeTab(
    name: String,
    root: BinarySizeNode,
    prologueText: String?,
    wasm: WasmBinaryProvider?,
): TableTabDescriptor? {
    val moduleSize = root.size
    val rootScope = SizeScope(base = 0, wasm = wasm)

    val topRows = labeledRows(root.children, rootScope)
    if (topRows.isEmpty()) return null

    fun columnsFor(rows: List<SizeRow>): List<ColumnDescriptor> = listOf(
        ColumnDescriptor(
            name = "Type",
            widthBehavior = ColumnWidth.Fill(),
        ) { index -> CellValue(rows[index].name) },
        ColumnDescriptor(
            name = "Size",
            widthBehavior = ColumnWidth.Measured("000.00 KiB"),
        ) { index -> CellValue(rows[index].size, formatSize(rows[index].size)) },
        ColumnDescriptor(
            name = "%",
            widthBehavior = ColumnWidth.Measured("100.0%"),
        ) { index ->
            val pct = rows[index].node.percentage(moduleSize)
            CellValue(pct, "${formatOneDecimal(pct)}%")
        },
        ColumnDescriptor(
            name = "Offset",
            widthBehavior = ColumnWidth.Measured("0x00000000"),
        ) { index ->
            val offset = rows[index].offset
            CellValue(offset, "0x${offset.toString(16).uppercase()}")
        },
    )

    fun selectionFor(row: SizeRow): RowSelectionData? =
        if (row.size > 0) RowSelectionData(row.offset, row.size) else null

    fun childRowsFor(row: SizeRow): TableTabChildRows? {
        val children = row.children
        if (children.isEmpty()) return null
        return TableTabChildRows(
            columns = columnsFor(children),
            rowCount = children.size,
            rowSelectionProvider = { index -> selectionFor(children[index]) },
            childrenProvider = { index -> childRowsFor(children[index]) },
        )
    }

    return TableTabDescriptor(
        name = name,
        columns = columnsFor(topRows),
        rowCount = topRows.size,
        defaultSortColumn = 1,
        defaultSortAscending = false,
        rowSelectionProvider = { index -> selectionFor(topRows[index]) },
        childrenProvider = { index -> childRowsFor(topRows[index]) },
        treemapData = buildTreemapData(root, topRows),
        prologueText = prologueText,
    )
}

/**
 * Wraps [nodes] in rows of [scope], numbering embedded modules and nested components in
 * definition order (`Module 0`, `Component 1`) so sibling containers can be told apart.
 */
private fun labeledRows(nodes: List<BinarySizeNode>, scope: SizeScope): List<SizeRow> {
    var modules = 0
    var components = 0
    return nodes.map { node ->
        val name = when (node) {
            is SizeNode.EmbeddedModule -> "${node.name} ${modules++}"
            is SizeNode.EmbeddedComponent -> "${node.name} ${components++}"
            else -> node.name
        }
        SizeRow(name, node, scope)
    }
}

/**
 * Builds [TreemapData] from the Top rows, with tree paths that correspond to row
 * indices in the table.
 *
 * Every row gets [TREEMAP_BASE_DEPTH] levels; a row of at least
 * [TREEMAP_DEEP_SIZE_FRACTION] of the file keeps expanding (down to [TREEMAP_MAX_DEPTH]),
 * so a large nested module or component is broken down instead of drawn as one block.
 * Below the base depth only children of that size are drawn (see [deepChildren]).
 * Function and data-segment detail is never drawn: its sizes are opcode counts and content
 * runs, not a partition of the parent's bytes.
 */
private fun buildTreemapData(root: BinarySizeNode, topRows: List<SizeRow>): TreemapData {
    val deepThreshold = (root.size * TREEMAP_DEEP_SIZE_FRACTION).toLong()

    fun buildNode(row: SizeRow, parentPath: List<Int>, index: Int, depth: Int): TreemapNode {
        val path = parentPath + index
        val node = row.node
        val expand = node !is SizeNode.Function && node !is SizeNode.DataSegment &&
            depth < TREEMAP_MAX_DEPTH && (depth < TREEMAP_BASE_DEPTH || row.size.toLong() >= deepThreshold)
        val children = when {
            !expand -> emptyList()
            depth + 1 < TREEMAP_BASE_DEPTH ->
                row.children.mapIndexed { childIndex, child -> buildNode(child, path, childIndex, depth + 1) }
            else -> deepChildren(row, path, depth, deepThreshold, ::buildNode)
        }
        return TreemapNode(name = row.name, size = row.size.toLong(), children = children, treePath = path)
    }

    val topNodes = topRows.mapIndexed { index, row -> buildNode(row, emptyList(), index, 0) }
    return TreemapData(
        root = TreemapNode(name = root.name, size = root.size.toLong(), children = topNodes),
        totalSize = root.size.toLong(),
    )
}

/**
 * Children drawn below [TREEMAP_BASE_DEPTH]: only those of at least [deepThreshold] bytes, so going
 * deep never adds microscopic squares. The rest are drawn as one `N smaller items` block (it
 * selects the parent row), which keeps the drawn children at their true share of the parent:
 * the layout scales siblings to fill the parent, so silently dropping them would inflate the rest.
 * Tree paths keep the table row index of each child.
 */
private fun deepChildren(
    row: SizeRow,
    path: List<Int>,
    depth: Int,
    deepThreshold: Long,
    buildNode: (SizeRow, List<Int>, Int, Int) -> TreemapNode,
): List<TreemapNode> {
    val (large, small) = row.children.withIndex().partition { it.value.size.toLong() >= deepThreshold }
    if (large.isEmpty()) return emptyList()
    val drawn = large.map { (childIndex, child) -> buildNode(child, path, childIndex, depth + 1) }
    if (small.isEmpty()) return drawn
    val rest = TreemapNode(
        name = "${small.size} smaller items",
        size = small.sumOf { it.value.size.toLong() },
        treePath = path,
    )
    return drawn + rest
}

/**
 * Resolves the children of [row], handling lazy expansion of functions, data segments,
 * and embedded modules/components.
 */
private fun resolveChildren(row: SizeRow): List<SizeRow> {
    val node = row.node
    val scope = row.scope
    if (node.children.isNotEmpty()) return node.children.map { SizeRow(it.name, it, scope) }

    return when (node) {
        // Embedded modules / nested components expand lazily from their own byte slice.
        is SizeNode.EmbeddedModule -> embeddedChildren(row, node.slice, isComponent = false)
        is SizeNode.EmbeddedComponent -> embeddedChildren(row, node.slice, isComponent = true)
        is SizeNode.Function -> {
            val builder = scope.builder ?: return emptyList()
            val record = scope.wasm?.parseCodeRecord(node.funcIndex) ?: return emptyList()
            builder.buildFunctionDetail(record).map { SizeRow(it.name, it, scope) }
        }
        is SizeNode.DataSegment -> {
            val builder = scope.builder ?: return emptyList()
            builder.buildDataSegmentDetail(scope.dataRecords, node.segmentIndex).map { SizeRow(it.name, it, scope) }
        }
        else -> emptyList()
    }
}

private fun embeddedChildren(row: SizeRow, nodeSlice: CommonByteBuffer?, isComponent: Boolean): List<SizeRow> {
    if (nodeSlice == null) return emptyList()
    // The node's slice belongs to the provider's cached size tree and is shared by every build of
    // this tab (and by other readers). On the JVM a slice of a memory-mapped file keeps its read
    // cursor, so whoever parsed it first leaves it at the end and every later parse here failed:
    // in the IDE the nested containers then came out empty, a single opaque block each.
    // Always parse an independent, rewound view.
    val slice = nodeSlice.duplicate().position(0)
    // The node spans the whole section (id + size prefix + content); the slice is the content,
    // which ends where the section ends.
    val base = row.offset + row.size - slice.limit()
    return try {
        if (isComponent) {
            val sub = (WasmParser().parse(slice) as? WasmParseSuccess)?.wasmModule ?: return emptyList()
            labeledRows(SizeAnalysisBuilder(slice, sub).buildTree().children, SizeScope(base, wasm = null))
        } else {
            val wasm = WasmBinaryProvider(slice)
            val tree = wasm.sizeAnalysis ?: return emptyList()
            labeledRows(tree.children, SizeScope(base, wasm))
        }
    } catch (_: Exception) {
        // A malformed nested binary must not take down the whole tab; it just stays unexpanded.
        emptyList()
    }
}
