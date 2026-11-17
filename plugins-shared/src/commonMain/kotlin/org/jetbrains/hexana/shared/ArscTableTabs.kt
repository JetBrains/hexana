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

    /**
     * Both tab names follow from the provider type alone. Answering here keeps
     * hosts from calling [tableTabs] just to learn them — for a resource table
     * that would parse the whole chunk tree and flatten every entry during
     * composition instead of on the thread that resolves the tab.
     */
    override fun availableTabNames(
        provider: BinaryProvider,
        context: TableTabsContext,
    ): Pair<List<String>, List<String>>? =
        if (provider is ArscBinaryProvider) listOf(HEADER_TAB, RESOURCES_TAB) to emptyList() else null

    /**
     * Resolves one advertised tab. The default implementation builds both to
     * hand back one, so a host resolving each name on its own coroutine — as
     * the IntelliJ editor does — would flatten the resource list twice.
     */
    override fun singleTab(
        name: String,
        provider: BinaryProvider,
        context: TableTabsContext,
    ): TableTabDescriptor? {
        if (provider !is ArscBinaryProvider) return null
        val arsc = provider.parsedArscFile ?: return null
        return when (name) {
            HEADER_TAB -> arscHeaderTab(arsc)
            RESOURCES_TAB -> arscResourcesTab(arsc)
            else -> null
        }
    }
}

private const val HEADER_TAB = "Header"
private const val RESOURCES_TAB = "Resources"

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
        name = HEADER_TAB,
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
        name = RESOURCES_TAB,
        columns = emptyList(),
        rowCount = 0,
        emptyMessage = "(no resources)",
    )

    val values = resources.map { arsc.formatEntryValue(it.entry) }

    return TableTabDescriptor(
        name = RESOURCES_TAB,
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
