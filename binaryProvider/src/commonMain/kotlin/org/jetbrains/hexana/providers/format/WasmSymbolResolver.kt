package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.document.format.SymbolResolver
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

/**
 * WASM symbol resolver. Resolution chain for the Function namespace:
 *
 * 1. **Name section** — `funcIdx → name` from the `name` custom section
 * 2. **DWARF `DW_AT_name`** — subprogram name from `.debug_info`
 * 3. **DWARF `DW_AT_linkage_name`** — typically a mangled symbol; useful when
 *    `DW_AT_name` is absent but the binary still carries linker-visible names
 * 4. **Exports** — exported function bindings (export bindings are not really "names")
 * 4b. **Symbol map `byIndex`** — a `--emit-symbol-map` `funcIndex:name` entry, when supplied.
 *    Acts as an external name section: restores names on a stripped/minified module.
 * 5. **Exports** — exported function bindings (export bindings are not really "names");
 *    a symbol-map `minified:original` `translate` entry de-minifies the binding when present.
 * 6. **Imports** — for an imported function index, its `module.field` binding (also passed
 *    through the symbol map's `translate`). This is the stable identifier the host resolves
 *    by name, so it is a strong matching anchor even when the name section and DWARF are
 *    absent. Last in the chain — it only fills indices the others miss.
 *
 * The first non-null win. The UI applies a final `$func<idx>` fallback when all miss.
 *
 * Function indices count imported functions first, then code-section-defined
 * functions (per the WASM spec). All sources speak this combined index space.
 */
public class WasmSymbolResolver(
    private val provider: WasmBinaryProvider,
    private val dwarfIndex: WasmDwarfNameIndex? = null,
    private val symbolMap: WasmSymbolMap? = null,
) : SymbolResolver {

    private val funcExportNames: Map<Int, String> by lazy {
        provider.exports
            .filterIsInstance<WasmFunctionExportRecordEntry>()
            .associate { it.index.toInt() to it.name }
    }

    private val funcNames: Map<Int, String>? by lazy {
        provider.nameSection?.functionSubsection?.namemap
    }

    // Imported functions occupy global function indices 0..importFuncCount-1 in
    // import-section order; only WasmTypeIndexExternalType imports are functions.
    private val funcImportNames: Map<Int, String> by lazy {
        provider.imports
            .filter { it.type is WasmTypeIndexExternalType }
            .mapIndexed { funcIdx, rec -> funcIdx to "${rec.prefix}.${rec.name}" }
            .toMap()
    }

    private fun deminify(name: String): String = symbolMap?.translate?.get(name) ?: name

    override fun resolve(kind: SymbolKind, index: Int): String? = when (kind) {
        SymbolKind.FUNCTION ->
            funcNames?.get(index)
                ?: dwarfIndex?.nameFor(index)
                ?: dwarfIndex?.linkageNameFor(index)
                ?: symbolMap?.byIndex?.get(index)
                ?: funcExportNames[index]?.let(::deminify)
                ?: funcImportNames[index]?.let(::deminify)
        // Other namespaces deferred until the WAT printer needs them.
        else -> null
    }
}
