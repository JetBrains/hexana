package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ClrResourcesBinaryProvider
import org.jetbrains.hexana.providers.pe.clr.ClrResource
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesFile

/**
 * Table tabs for .NET `.resources` files: the reader header and the list of resources with their
 * decoded values. The same resource columns serve embedded `.resources` blobs in an assembly's
 * Resources tab (see [clrResourceChildRows]).
 */
class ClrResourcesTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ClrResourcesBinaryProvider) return emptyList()
        val resources = provider.parsedResources ?: return emptyList()
        return listOf(resourcesHeaderTab(resources), resourcesTab(resources))
    }

    /** Both names follow from the provider type, so hosts need not parse to list them. */
    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? =
        if (provider is ClrResourcesBinaryProvider) listOf(HEADER_TAB, RESOURCES_TAB) to emptyList() else null

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is ClrResourcesBinaryProvider) return null
        val resources = provider.parsedResources ?: return null
        return when (name) {
            HEADER_TAB -> resourcesHeaderTab(resources)
            RESOURCES_TAB -> resourcesTab(resources)
            else -> null
        }
    }
}

private const val HEADER_TAB = "Header"
private const val RESOURCES_TAB = "Resources"

private fun resourcesHeaderTab(file: ClrResourcesFile): TableTabDescriptor {
    val rows = buildList {
        add(HeaderRow("Reader Type", file.readerType.ifEmpty { "(not recorded)" }, file.headerRange))
        add(HeaderRow("Resource Set Type", file.resourceSetType.ifEmpty { "(not recorded)" }, file.headerRange))
        add(HeaderRow("Manager Header Version", file.managerHeaderVersion.toString(), file.headerRange))
        add(HeaderRow("Format Version", file.version.toString() + if (file.version != 2) " (values not decoded)" else "", file.headerRange))
        add(HeaderRow("Resources", file.resources.size.toString(), file.nameSectionRange))
        file.types.forEachIndexed { index, type -> add(HeaderRow("Type ${index + 1}", type, file.headerRange)) }
        file.diagnostics.forEach { add(HeaderRow("Warning", it.message, it.range)) }
    }
    return keyValueTab(HEADER_TAB, rows, labelWidth = ColumnWidth.Fill(0.3f))
}

private fun resourcesTab(file: ClrResourcesFile): TableTabDescriptor {
    val resources = file.resources.sortedBy { it.name }
    if (resources.isEmpty()) {
        return TableTabDescriptor(name = RESOURCES_TAB, columns = emptyList(), rowCount = 0, emptyMessage = "(no resources)")
    }
    return TableTabDescriptor(
        name = RESOURCES_TAB,
        columns = resourceColumns(resources),
        rowCount = resources.size,
        rowSelectionProvider = { resources.getOrNull(it)?.let(::resourceSelection) },
        alwaysShowSearch = true,
        searchPlaceholder = "Search resources",
        showIdColumn = false,
    )
}

/**
 * The entries of an embedded `.resources` blob, as child rows under the assembly's Resources
 * tab: the same four columns the parent has (Name, Visibility, Location, Size), filled with the
 * resource's name, type, value and size.
 */
internal fun clrResourceChildRows(file: ClrResourcesFile): TableTabChildRows? {
    val resources = file.resources.sortedBy { it.name }.takeIf { it.isNotEmpty() } ?: return null
    return TableTabChildRows(
        columns = listOf(
            ColumnDescriptor("Name", ColumnWidth.Fill(0.45f)) { CellValue(resources[it].name) },
            ColumnDescriptor("Visibility") { CellValue(shortType(resources[it].type)) },
            ColumnDescriptor("Location", ColumnWidth.Fill(0.25f)) { CellValue(resources[it].value.toDisplayLiteral()) },
            ColumnDescriptor("Size", ColumnWidth.Fill()) { CellValue((resources[it].dataRange?.size ?: 0).toLong(), resources[it].dataRange?.size?.toString().orEmpty()) },
        ),
        rowCount = resources.size,
        rowSelectionProvider = { resources.getOrNull(it)?.let(::resourceSelection) },
    )
}

private fun resourceColumns(resources: List<ClrResource>): List<ColumnDescriptor> = listOf(
    col("Name", ColumnWidth.Fill(0.32f)) { CellValue(resources[it].name) },
    col("Type") { CellValue(shortType(resources[it].type), tooltip = resources[it].type) },
    col("Value", ColumnWidth.Fill(0.55f)) { CellValue(resources[it].value.toDisplayLiteral(), tooltip = resources[it].stringValue) },
    col("Size", ColumnWidth.Fill()) { CellValue((resources[it].dataRange?.size ?: 0).toLong(), resources[it].dataRange?.size?.toString().orEmpty()) },
)

/** Binary values select their payload; everything else the whole value, type code included. */
private fun resourceSelection(resource: ClrResource): RowSelectionData? =
    (resource.payloadRange ?: resource.dataRange ?: resource.nameRange).let(::selection)

/** `System.Drawing.Size, System.Drawing, Version=…` → `System.Drawing.Size`. */
private fun shortType(type: String): String = type.substringBefore(',')
