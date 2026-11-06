package org.jetbrains.hexana.shared

import org.jetbrains.hexana.arrow.ArrowSchema
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ArrowFormat
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.DecodedTable

/** [TableTabsProvider] for Apache Arrow IPC binaries (file and stream formats). */
class ArrowTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ArrowBinaryProvider) return emptyList()
        val schema = provider.schema ?: return emptyList()
        if (schema.fieldCount == 0) return emptyList()
        return buildList {
            add(schemaTab(schema))
            messagesTab(provider)?.let { add(it) }
            add(metadataTab(provider, schema))
            add(dataTab(provider.decodedTable))
        }
    }

    private fun schemaTab(schema: ArrowSchema): TableTabDescriptor {
        return TableTabDescriptor(
            name = "Schema",
            columns = listOf(
                col("Name", ColumnWidth.Fill(0.5f)) { CellValue(schema.field(it).name ?: "") },
                col("Nullable") { CellValue(if (schema.field(it).nullable) "yes" else "no") },
                col("Children") { CellValue(schema.field(it).childCount) },
            ),
            rowCount = schema.fieldCount,
        )
    }

    private fun messagesTab(provider: ArrowBinaryProvider): TableTabDescriptor? {
        return when (provider.format) {
            ArrowFormat.STREAM -> {
                val messages = provider.streamContents?.messages ?: return null
                if (messages.isEmpty()) return null
                TableTabDescriptor(
                    name = "Messages",
                    columns = listOf(
                        col("#") { CellValue(it) },
                        col("Type") { CellValue(messages[it].message.headerType.name) },
                        col("Body Length") { CellValue(messages[it].message.bodyLength) },
                    ),
                    rowCount = messages.size,
                )
            }
            ArrowFormat.FILE -> {
                val footer = provider.fileFooter ?: return null
                val count = footer.recordBatchBlockCount
                if (count == 0) return null
                TableTabDescriptor(
                    name = "Record Batches",
                    columns = listOf(
                        col("#") { CellValue(it) },
                        col("Dictionary Batches") { CellValue(footer.dictionaryCount) },
                    ),
                    rowCount = count,
                )
            }
        }
    }

    private fun metadataTab(provider: ArrowBinaryProvider, schema: ArrowSchema): TableTabDescriptor {
        val formatLabel = when (provider.format) {
            ArrowFormat.FILE -> "File"
            ArrowFormat.STREAM -> "Stream"
        }
        val messageCount = when (provider.format) {
            ArrowFormat.STREAM -> provider.streamContents?.messages?.size?.toString() ?: "0"
            ArrowFormat.FILE -> provider.fileFooter?.recordBatchBlockCount?.toString() ?: "0"
        }
        val rows = listOf(
            "Format" to formatLabel,
            "Field Count" to schema.fieldCount.toString(),
            "Record Batches" to messageCount,
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
            // Recognized as Arrow but no rows decoded (empty, or a malformed record-batch
            // offset the bounds-checked reader rejected). Show an explicit empty state
            // rather than silently omitting the tab.
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