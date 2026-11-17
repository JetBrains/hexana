package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ProtobufMessageBinaryProvider

/**
 * [TableTabsProvider] for an opaque Protobuf message decoded without a schema
 * (via [ProtobufMessageBinaryProvider]). Renders the flat, positionally-pathed
 * field list from the raw wire decoder as a single indented table.
 */
class ProtobufMessageTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ProtobufMessageBinaryProvider) return emptyList()
        val fields = provider.decodedFields
        if (fields.isEmpty()) return emptyList()

        return listOf(
            TableTabDescriptor(
                name = "Protobuf Message",
                columns = listOf(
                    // Indent by depth for a tree look; sort by the raw dotted path.
                    col("Path", ColumnWidth.Fill(0.30f)) {
                        val f = fields[it]
                        CellValue(f.path, representation = "  ".repeat(f.depth) + f.path)
                    },
                    col("Field#") {
                        val n = fields[it].fieldNumber
                        CellValue(n, representation = if (n == -1) "-" else n.toString())
                    },
                    col("Type") { CellValue(fields[it].typeGuess) },
                    col("Value", ColumnWidth.Fill(0.35f)) { CellValue(fields[it].value) },
                    col("Offset") {
                        val off = fields[it].offset
                        CellValue(off, representation = hex(off))
                    },
                    col("Length") { CellValue(fields[it].length) },
                ),
                rowCount = fields.size,
                // Single-range row -> hex link: select the field's absolute byte range.
                // Error/sentinel rows (fieldNumber == -1) and zero-length rows are ignored.
                rowSelectionProvider = {
                    val f = fields[it]
                    if (f.length > 0 && f.offset >= 0) RowSelectionData(f.offset, f.length) else null
                },
            ),
        )
    }
}
