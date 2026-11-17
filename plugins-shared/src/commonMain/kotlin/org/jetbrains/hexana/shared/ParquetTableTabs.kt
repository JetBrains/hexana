package org.jetbrains.hexana.shared

import org.jetbrains.hexana.parquet.ColumnChunk
import org.jetbrains.hexana.parquet.FileMetaData
import org.jetbrains.hexana.parquet.RowGroup
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.DecodedTable
import org.jetbrains.hexana.providers.ParquetBinaryProvider

/** [TableTabsProvider] for Apache Parquet binaries. */
class ParquetTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ParquetBinaryProvider) return emptyList()
        val meta = provider.metadata ?: return emptyList()
        val leaves = provider.leafElements
        if (leaves.isEmpty()) return emptyList()
        return buildList {
            add(schemaTab(provider))
            add(rowGroupsTab(meta))
            add(metadataTab(meta))
            add(dataTab(provider.decodedTable))
        }
    }

    private fun schemaTab(provider: ParquetBinaryProvider): TableTabDescriptor {
        val leaves = provider.leafElements
        return TableTabDescriptor(
            name = "Schema",
            columns = listOf(
                col("Name", ColumnWidth.Fill(0.4f)) { CellValue(leaves[it].name) },
                col("Type") { CellValue(leaves[it].type?.name ?: "") },
                col("Repetition") { CellValue(leaves[it].repetitionType?.name ?: "") },
            ),
            rowCount = leaves.size,
        )
    }

    private fun rowGroupsTab(meta: FileMetaData): TableTabDescriptor {
        val rowGroups = meta.rowGroups
        return TableTabDescriptor(
            name = "Row Groups",
            columns = listOf(
                col("#") { CellValue(it) },
                col("Rows") { CellValue(rowGroups[it].numRows) },
                col("Total Size") { CellValue(rowGroups[it].totalByteSize) },
                col("Columns") { CellValue(rowGroups[it].columns.size) },
            ),
            rowCount = rowGroups.size,
            childrenProvider = { i -> rowGroupChildren(rowGroups[i]) },
        )
    }

    private fun rowGroupChildren(rowGroup: RowGroup): TableTabChildRows {
        val columns = rowGroup.columns
        return TableTabChildRows(
            columns = listOf(
                col("Path") { CellValue(columns[it].metaData?.pathInSchema?.joinToString(".") ?: "") },
                col("Type") { CellValue(columns[it].metaData?.type?.name ?: "") },
                col("Codec") { CellValue(columns[it].metaData?.codec?.name ?: "") },
                col("Num Values") { CellValue(columns[it].metaData?.numValues ?: 0L) },
            ),
            rowCount = columns.size,
        )
    }

    private fun metadataTab(meta: FileMetaData): TableTabDescriptor {
        val rows = listOf(
            "Version" to meta.version.toString(),
            "Created By" to (meta.createdBy ?: ""),
            "Total Rows" to meta.numRows.toString(),
            "Row Groups" to meta.rowGroups.size.toString(),
            "Schema Elements" to meta.schema.size.toString(),
        )
        return TableTabDescriptor(
            name = "Metadata",
            columns = listOf(
                col("Property", ColumnWidth.Fill(0.35f)) { CellValue(rows[it].first) },
                col("Value", ColumnWidth.Fill()) { CellValue(rows[it].second) },
            ),
            rowCount = rows.size,
        )
    }

    private fun dataTab(table: DecodedTable?): TableTabDescriptor {
        if (table == null || table.columnNames.isEmpty() || table.rowCount == 0) {
            // Recognized as Parquet but no rows decoded (empty file, or a malformed
            // page/offset the bounds-checked decoder rejected). Show an explicit empty
            // state rather than silently omitting the tab.
            return TableTabDescriptor(
                name = "Data",
                columns = listOf(col("Data") { CellValue("") }),
                rowCount = 0,
                emptyMessage = "No rows decoded — the file may be empty or malformed.",
            )
        }
        return TableTabDescriptor(
            name = "Data",
            columns = table.columnNames.indices.map { c ->
                col(table.columnNames[c], ColumnWidth.MeasuredMin(table.columnNames[c])) { row ->
                    val cell = table.cell(row, c)
                    val repr = when {
                        !table.columnFullyDecoded(c) ->
                            "<not decoded: ${table.columnUndecodedReason(c) ?: "?"}>"
                        cell.isNull -> "null"
                        else -> cell.value?.toString() ?: "null"
                    }
                    CellValue(cell.value, repr)
                }
            },
            rowCount = table.rowCount,
            rowSelectionProvider = { row ->
                table.rowRanges(row).firstOrNull()?.let { RowSelectionData(it.offset, it.size.toLong()) }
            },
            rowHighlightRangesProvider = { row ->
                table.rowRanges(row).map { HexRange(it.offset, it.size) }
            },
            prologueText = if (table.truncated) "Showing first ${table.rowCount} rows" else null,
        )
    }
}
