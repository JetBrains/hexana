package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dex.*
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider

class DexTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is DexBinaryProvider) return emptyList()
        val dex = provider.parsedDexFile ?: return emptyList()
        return listOfNotNull(
            dexHeaderTab(dex),
            dexClassesTab(dex, provider),
        )
    }
}

private fun dexHeaderTab(dex: DexFile): TableTabDescriptor {
    val h = dex.header
    val rows = listOf(
        "Version" to h.version,
        "File size" to "${h.fileSize} bytes",
        "Header size" to "${h.headerSize} bytes",
        "Checksum" to "0x${h.checksum.toUInt().toString(16).padStart(8, '0')}",
        "Signature" to h.signature.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        "Strings" to h.stringIdsSize.toString(),
        "Types" to h.typeIdsSize.toString(),
        "Prototypes" to h.protoIdsSize.toString(),
        "Fields" to h.fieldIdsSize.toString(),
        "Methods" to h.methodIdsSize.toString(),
        "Classes" to h.classDefsSize.toString(),
    )
    return TableTabDescriptor(
        name = "Header",
        columns = listOf(
            col("Property", ColumnWidth.MeasuredMin("Property")) { CellValue(rows[it].first) },
            col("Value", ColumnWidth.Fill()) { CellValue(rows[it].second) },
        ),
        rowCount = rows.size,
        showIdColumn = false,
        priority = TabPriority.High,
        prologueText = "Android DEX file",
    )
}

private fun dexClassesTab(dex: DexFile, provider: DexBinaryProvider): TableTabDescriptor {
    val classDefs = dex.classDefs
    if (classDefs.isEmpty()) return TableTabDescriptor(
        name = "Classes",
        columns = emptyList(),
        rowCount = 0,
        emptyMessage = "(no classes)",
    )

    val classNames = classDefs.map { cd ->
        dex.typeName(cd.classIdx) ?: "<#${cd.classIdx}>"
    }
    val accessFlags = classDefs.map { renderDexClassAccessFlags(it.accessFlags) }
    val superNames = classDefs.map { cd ->
        if (cd.superclassIdx == -1 || cd.superclassIdx == 0xFFFFFFFF.toInt()) ""
        else dex.typeName(cd.superclassIdx) ?: "<#${cd.superclassIdx}>"
    }

    // Lazily parse class data for children
    val classDataCache = HashMap<Int, DexClassData?>()
    fun classData(index: Int): DexClassData? = classDataCache.getOrPut(index) {
        try {
            DexFileParser.parseClassData(provider.bytes, classDefs[index])
        } catch (_: Exception) {
            null
        }
    }

    val interfacesCache = HashMap<Int, List<Int>>()
    fun interfaces(index: Int): List<Int> = interfacesCache.getOrPut(index) {
        try {
            DexFileParser.parseTypeList(provider.bytes, classDefs[index].interfacesOff)
        } catch (_: Exception) {
            emptyList()
        }
    }

    return TableTabDescriptor(
        name = "Classes",
        columns = listOf(
            col("Index", ColumnWidth.MeasuredMin("9999")) { CellValue(it) },
            col("Class", ColumnWidth.Fill()) { CellValue(classNames[it]) },
            col("Access", ColumnWidth.MeasuredMin("public abstract interface")) { CellValue(accessFlags[it]) },
            col("Superclass", ColumnWidth.Fill(0.4f)) { CellValue(superNames[it]) },
        ),
        rowCount = classDefs.size,
        defaultSortColumn = 0,
        alwaysShowSearch = true,
        searchPlaceholder = "Search classes",
        priority = TabPriority.High,
        childrenProvider = { index ->
            dexClassChildren(dex, provider, index, classData(index), interfaces(index))
        },
    )
}

private fun dexClassChildren(
    dex: DexFile,
    provider: DexBinaryProvider,
    classDefIndex: Int,
    classData: DexClassData?,
    interfaces: List<Int>,
): TableTabChildRows {
    val classDef = dex.classDefs[classDefIndex]
    val className = dex.typeName(classDef.classIdx) ?: "<#${classDef.classIdx}>"

    // Build child rows: info section, then fields, then methods
    data class ChildRow(val category: String, val detail: String)

    val rows = mutableListOf<ChildRow>()

    // Info rows
    val flags = renderDexClassAccessFlags(classDef.accessFlags)
    if (flags.isNotEmpty()) rows += ChildRow("Info", "access: $flags")
    val superName = if (classDef.superclassIdx == -1 || classDef.superclassIdx == 0xFFFFFFFF.toInt()) null
    else dex.typeName(classDef.superclassIdx)
    if (superName != null) rows += ChildRow("Info", "extends: $superName")
    if (interfaces.isNotEmpty()) {
        for (typeIdx in interfaces) {
            val ifaceName = dex.typeName(typeIdx) ?: "<#$typeIdx>"
            rows += ChildRow("Info", "implements: $ifaceName")
        }
    }
    val sourceFile = if (classDef.sourceFileIdx == -1 || classDef.sourceFileIdx == 0xFFFFFFFF.toInt()) null
    else dex.string(classDef.sourceFileIdx)
    if (sourceFile != null) rows += ChildRow("Info", "source file: $sourceFile")

    // Fields
    if (classData != null) {
        for (f in classData.staticFields) {
            val fieldId = dex.fieldIds.getOrNull(f.fieldIdx)
            val name = fieldId?.let { dex.string(it.nameIdx) } ?: "<#${f.fieldIdx}>"
            val type = fieldId?.let { dex.typeName(it.typeIdx) } ?: "?"
            val fFlags = renderDexFieldAccessFlags(f.accessFlags)
            val text = buildString {
                if (fFlags.isNotEmpty()) { append(fFlags); append(' ') }
                append(type); append(' '); append(name)
            }
            rows += ChildRow("Static field", text)
        }
        for (f in classData.instanceFields) {
            val fieldId = dex.fieldIds.getOrNull(f.fieldIdx)
            val name = fieldId?.let { dex.string(it.nameIdx) } ?: "<#${f.fieldIdx}>"
            val type = fieldId?.let { dex.typeName(it.typeIdx) } ?: "?"
            val fFlags = renderDexFieldAccessFlags(f.accessFlags)
            val text = buildString {
                if (fFlags.isNotEmpty()) { append(fFlags); append(' ') }
                append(type); append(' '); append(name)
            }
            rows += ChildRow("Instance field", text)
        }

        // Methods
        for (m in classData.directMethods) {
            val methodId = dex.methodIds.getOrNull(m.methodIdx)
            val name = methodId?.let { dex.string(it.nameIdx) } ?: "<#${m.methodIdx}>"
            val proto = methodId?.let { dex.protoIds.getOrNull(it.protoIdx) }
            val returnType = proto?.let { dex.typeName(it.returnTypeIdx) } ?: "?"
            val mFlags = renderDexMethodAccessFlags(m.accessFlags)
            val text = buildString {
                if (mFlags.isNotEmpty()) { append(mFlags); append(' ') }
                append(returnType); append(' '); append(name); append("()")
            }
            rows += ChildRow("Direct method", text)
        }
        for (m in classData.virtualMethods) {
            val methodId = dex.methodIds.getOrNull(m.methodIdx)
            val name = methodId?.let { dex.string(it.nameIdx) } ?: "<#${m.methodIdx}>"
            val proto = methodId?.let { dex.protoIds.getOrNull(it.protoIdx) }
            val returnType = proto?.let { dex.typeName(it.returnTypeIdx) } ?: "?"
            val mFlags = renderDexMethodAccessFlags(m.accessFlags)
            val text = buildString {
                if (mFlags.isNotEmpty()) { append(mFlags); append(' ') }
                append(returnType); append(' '); append(name); append("()")
            }
            rows += ChildRow("Virtual method", text)
        }
    }

    if (rows.isEmpty()) {
        rows += ChildRow("Info", "(no class data)")
    }

    return TableTabChildRows(
        columns = listOf(
            ColumnDescriptor("Index") { CellValue("") },
            ColumnDescriptor("Class", ColumnWidth.Fill()) { CellValue(rows[it].detail) },
            ColumnDescriptor("Access", ColumnWidth.MeasuredMin("public abstract interface")) { CellValue(rows[it].category) },
            ColumnDescriptor("Superclass", ColumnWidth.Fill(0.4f)) { CellValue("") },
        ),
        rowCount = rows.size,
    )
}
