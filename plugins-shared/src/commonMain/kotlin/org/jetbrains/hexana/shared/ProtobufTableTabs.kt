package org.jetbrains.hexana.shared

import org.jetbrains.hexana.protobuf.ProtoEnumValue
import org.jetbrains.hexana.protobuf.ProtoField
import org.jetbrains.hexana.protobuf.ProtoMessage
import org.jetbrains.hexana.protobuf.ProtoMethod
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ProtobufBinaryProvider

/** [TableTabsProvider] for Protocol Buffers FileDescriptorSet binaries. */
class ProtobufTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ProtobufBinaryProvider) return emptyList()
        val ds = provider.parsedDescriptorSet ?: return emptyList()
        val files = ds.files
        if (files.isEmpty()) return emptyList()

        // Flatten cross-file collections once; each row type carries the file name alongside
        // the parsed object so cell providers can reference both without nested indexing.
        data class MessageRow(val fileName: String, val message: ProtoMessage)
        data class FieldRow(val fileName: String, val messageName: String, val field: ProtoField)
        data class EnumValueRow(val fileName: String, val enumName: String, val value: ProtoEnumValue)
        data class MethodRow(val fileName: String, val serviceName: String, val method: ProtoMethod)

        val messages = files.flatMap { f -> f.messages.map { MessageRow(f.name, it) } }
        val fields = messages.flatMap { mr ->
            mr.message.fields.map { FieldRow(mr.fileName, mr.message.qualifiedName, it) }
        }
        val enumValues = files.flatMap { f ->
            f.enums.flatMap { e -> e.values.map { EnumValueRow(f.name, e.qualifiedName, it) } }
        }
        val methods = files.flatMap { f ->
            f.services.flatMap { s -> s.methods.map { MethodRow(f.name, s.name, it) } }
        }

        return buildList {
            // Files tab — always present (files is non-empty, checked above).
            add(TableTabDescriptor(
                name = "Files",
                columns = listOf(
                    col("File", ColumnWidth.Fill(0.30f)) { CellValue(files[it].name) },
                    col("Package") { CellValue(files[it].pkg) },
                    col("Syntax") { CellValue(files[it].syntax) },
                    col("Messages") { CellValue(files[it].messages.count { m -> '.' !in m.qualifiedName }) },
                    col("Enums") { CellValue(files[it].enums.count { e -> '.' !in e.qualifiedName }) },
                    col("Services") { CellValue(files[it].services.size) },
                    col("Dependencies", ColumnWidth.Fill()) { CellValue(files[it].dependencies.joinToString(", ")) },
                ),
                rowCount = files.size,
            ))

            if (messages.isNotEmpty()) {
                add(TableTabDescriptor(
                    name = "Messages",
                    columns = listOf(
                        col("File", ColumnWidth.Fill(0.25f)) { CellValue(messages[it].fileName) },
                        col("Message", ColumnWidth.Fill(0.40f)) { CellValue(messages[it].message.qualifiedName) },
                        col("Fields") { CellValue(messages[it].message.fields.size) },
                        col("Nested") { CellValue(messages[it].message.nested.size) },
                        col("Enums") { CellValue(messages[it].message.enums.size) },
                    ),
                    rowCount = messages.size,
                ))
            }

            if (fields.isNotEmpty()) {
                add(TableTabDescriptor(
                    name = "Fields",
                    columns = listOf(
                        col("File") { CellValue(fields[it].fileName) },
                        col("Message", ColumnWidth.Fill(0.30f)) { CellValue(fields[it].messageName) },
                        col("Field", ColumnWidth.Fill(0.20f)) { CellValue(fields[it].field.name) },
                        col("Number") { CellValue(fields[it].field.number) },
                        col("Label") { CellValue(fields[it].field.label) },
                        col("Type") { CellValue(fields[it].field.type) },
                        col("Type ref", ColumnWidth.Fill()) { CellValue(fields[it].field.typeRef) },
                    ),
                    rowCount = fields.size,
                ))
            }

            if (enumValues.isNotEmpty()) {
                add(TableTabDescriptor(
                    name = "Enums",
                    columns = listOf(
                        col("File") { CellValue(enumValues[it].fileName) },
                        col("Enum", ColumnWidth.Fill(0.35f)) { CellValue(enumValues[it].enumName) },
                        col("Value", ColumnWidth.Fill(0.25f)) { CellValue(enumValues[it].value.name) },
                        col("Number") { CellValue(enumValues[it].value.number) },
                    ),
                    rowCount = enumValues.size,
                ))
            }

            if (methods.isNotEmpty()) {
                add(TableTabDescriptor(
                    name = "Services",
                    columns = listOf(
                        col("File") { CellValue(methods[it].fileName) },
                        col("Service") { CellValue(methods[it].serviceName) },
                        col("Method", ColumnWidth.Fill(0.25f)) { CellValue(methods[it].method.name) },
                        col("Input", ColumnWidth.Fill(0.20f)) { CellValue(methods[it].method.inputType) },
                        col("Output", ColumnWidth.Fill(0.20f)) { CellValue(methods[it].method.outputType) },
                        col("Client streaming") { CellValue(methods[it].method.clientStreaming.toString()) },
                        col("Server streaming") { CellValue(methods[it].method.serverStreaming.toString()) },
                    ),
                    rowCount = methods.size,
                ))
            }
        }
    }
}
