package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

/**
 * [TableTabsProvider] for component-model Wasm binaries.
 *
 * Produces table-based tabs: Exports, Imports, Modules, Components.
 *
 * No Compatibility tab: a component itself uses no detectable proposal, and the proposals of
 * the core modules nested inside it belong to those modules, which get their own tab when
 * opened. See the exclusion list in [org.jetbrains.hexana.wasm.analysis.WasmProposal].
 */
class WasmComponentTableTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is WasmComponentBinaryProvider) return emptyList()
        if (!provider.canProvideWasmModule()) return emptyList()
        return buildList {
            componentExportsTab(provider)?.let { add(it) }
            componentImportsTab(provider)?.let { add(it) }
            componentModulesTab(provider)?.let { add(it) }
            componentNestedComponentsTab(provider)?.let { add(it) }
            // Forces the lazy parseProblems; bounded by the core-instance count
            // and tableTabs runs off-EDT.
            problemsTabDescriptor(provider.parseProblems)?.let { add(it) }
        }
    }
}

private fun componentExportsTab(provider: WasmComponentBinaryProvider): TableTabDescriptor? {
    val exports = provider.exports
    if (exports.isEmpty()) return null

    return TableTabDescriptor(
        name = "Exports",
        columns = listOf(
            ColumnDescriptor(
                name = "Name",
                widthBehavior = ColumnWidth.Measured(UNIVERSAL_QUALIFIED_NAME_MEASURE),
            ) { index ->
                val record = exports[index]
                CellValue(record.exportName + (record.version ?: ""))
            },
            ColumnDescriptor(
                name = "Sort Index",
                widthBehavior = ColumnWidth.MeasuredMin("Component(000)"),
            ) { index ->
                CellValue(componentSortIndexLabel(exports[index].sortIndex) ?: "")
            },
            ColumnDescriptor(
                name = "Extern Description",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val desc = exports[index].externDescription
                CellValue(desc?.let { componentExternDescriptionLabel(it) } ?: "")
            },
        ),
        rowCount = exports.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
        rowSelectionProvider = { index ->
            val record = exports.getOrNull(index)
            record?.let { RowSelectionData(it.offset, it.size) }
        },
    )
}

private fun componentImportsTab(provider: WasmComponentBinaryProvider): TableTabDescriptor? {
    val imports = provider.imports
    if (imports.isEmpty()) return null

    return TableTabDescriptor(
        name = "Imports",
        columns = listOf(
            ColumnDescriptor(
                name = "Name",
                widthBehavior = ColumnWidth.Measured(UNIVERSAL_QUALIFIED_NAME_MEASURE),
            ) { index ->
                CellValue(imports[index].importName + (imports[index].version ?: ""))
            },
            ColumnDescriptor(
                name = "Extern Description",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                CellValue(componentExternDescriptionLabel(imports[index].externDescription))
            },
        ),
        rowCount = imports.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
        rowSelectionProvider = { index ->
            val record = imports.getOrNull(index)
            record?.let { RowSelectionData(it.offset, it.size) }
        },
    )
}

private fun componentModulesTab(provider: WasmComponentBinaryProvider): TableTabDescriptor? {
    val modules = provider.modules
    if (modules.isEmpty()) return null

    return TableTabDescriptor(
        name = "Modules",
        columns = listOf(
            ColumnDescriptor(
                name = "Data",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                CellValue("module:@${modules[index].contentOffset.toHexString()}")
            },
        ),
        rowCount = modules.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
    )
}

private fun componentNestedComponentsTab(provider: WasmComponentBinaryProvider): TableTabDescriptor? {
    if (provider.nestedComponents.isEmpty()) return null
    // The parser tracks a single nested-component section; its content offset
    // labels the one row this tab currently has.
    val section = provider.wasmModule?.componentSection ?: return null

    return TableTabDescriptor(
        name = "Components",
        columns = listOf(
            ColumnDescriptor(
                name = "Data",
                widthBehavior = ColumnWidth.Fill(),
            ) {
                CellValue("component:@${section.contentOffset.toHexString()}")
            },
        ),
        rowCount = provider.nestedComponents.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
    )
}

