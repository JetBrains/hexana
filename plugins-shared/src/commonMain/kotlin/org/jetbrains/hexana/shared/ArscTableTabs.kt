package org.jetbrains.hexana.shared

import org.jetbrains.hexana.arsc.ArscFile
import org.jetbrains.hexana.arsc.ArscResource
import org.jetbrains.hexana.arsc.hexResourceId
import org.jetbrains.hexana.providers.ArscBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider

/**
 * Table tabs for Android binary resource tables (`resources.arsc`): a summary
 * of the table's packages and a flat list of every defined resource.
 */
class ArscTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ArscBinaryProvider) return emptyList()
        val arsc = provider.parsedArscFile ?: return emptyList()
        return listOf(
            arscHeaderTab(arsc),
            arscResourcesTab(arsc),
        )
    }
}

private fun arscHeaderTab(arsc: ArscFile): TableTabDescriptor {
    val rows = buildList {
        add("Packages" to arsc.packageCount.toString())
        add("Value strings" to arsc.valueStrings.strings.size.toString())
        add("String encoding" to if (arsc.valueStrings.isUtf8) "UTF-8" else "UTF-16")
        add("Resources" to arsc.resourceCount.toString())
        for (pkg in arsc.packages) {
            add("Package ${hexPackageId(pkg.id)}" to pkg.name)
            add("  Resource types" to pkg.typeStrings.strings.size.toString())
            add("  Resource names" to pkg.keyStrings.strings.size.toString())
            add("  Configurations" to pkg.types.map { it.configuration.qualifier }.distinct().size.toString())
        }
    }
    return TableTabDescriptor(
        name = "Header",
        columns = listOf(
            col("Property", ColumnWidth.MeasuredMin("  Resource types")) { CellValue(rows[it].first) },
            col("Value", ColumnWidth.Fill()) { CellValue(rows[it].second) },
        ),
        rowCount = rows.size,
        showIdColumn = false,
        priority = TabPriority.High,
        prologueText = "Android resource table",
    )
}

private fun arscResourcesTab(arsc: ArscFile): TableTabDescriptor {
    val resources = arsc.resources()
    if (resources.isEmpty()) return TableTabDescriptor(
        name = "Resources",
        columns = emptyList(),
        rowCount = 0,
        emptyMessage = "(no resources)",
    )

    val values = resources.map { arsc.formatEntryValue(it.entry) }

    return TableTabDescriptor(
        name = "Resources",
        columns = listOf(
            col("ID", ColumnWidth.MeasuredMin("0x7f0f0000")) { CellValue(hexResourceId(resources[it].id)) },
            col("Type", ColumnWidth.MeasuredMin("interpolator")) { CellValue(resources[it].typeName) },
            col("Name", ColumnWidth.Fill(0.4f)) { CellValue(resources[it].name) },
            col("Config", ColumnWidth.MeasuredMin("en-rUS-xxxhdpi")) {
                CellValue(resources[it].configuration.qualifier)
            },
            col("Value", ColumnWidth.Fill()) { CellValue(values[it]) },
        ),
        rowCount = resources.size,
        defaultSortColumn = 0,
        alwaysShowSearch = true,
        searchPlaceholder = "Search resources",
        priority = TabPriority.High,
        // Only complex entries have anything to expand into.
        childrenProvider = { index -> arscResourceChildren(arsc, resources[index]) },
    )
}

/** Bag items of a complex resource — a style's attributes, an array's elements. */
private fun arscResourceChildren(arsc: ArscFile, resource: ArscResource): TableTabChildRows? {
    val entry = resource.entry
    if (!entry.isComplex || entry.mapValues.isEmpty()) return null

    val names = entry.mapValues.map { arsc.formatResourceReference(it.nameRef) }
    val values = entry.mapValues.map { arsc.formatValue(it.value) }

    return TableTabChildRows(
        columns = listOf(
            ColumnDescriptor("ID") { CellValue("") },
            ColumnDescriptor("Type") { CellValue("item") },
            ColumnDescriptor("Name", ColumnWidth.Fill(0.4f)) { CellValue(names[it]) },
            ColumnDescriptor("Config") { CellValue("") },
            ColumnDescriptor("Value", ColumnWidth.Fill()) { CellValue(values[it]) },
        ),
        rowCount = entry.mapValues.size,
    )
}

/** Package IDs are a single byte, e.g. `0x7f` for an application package. */
private fun hexPackageId(id: Int): String = "0x" + id.toString(16).padStart(2, '0')
