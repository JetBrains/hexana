package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.document.format.SymbolResolver
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

/**
 * WASM symbol resolver. Resolution chain for the Function namespace:
 *
 * 1. **Name section** — `funcIdx → name` from the `name` custom section
 * 2. **DWARF `DW_AT_name`** — subprogram name from `.debug_info`
 * 3. **DWARF `DW_AT_linkage_name`** — typically a mangled symbol; useful when
 *    `DW_AT_name` is absent but the binary still carries linker-visible names
 * 4. **Exports** — exported function bindings (last resort because export
 *    bindings are not really "names")
 *
 * The first non-null win. The UI applies a final `$func<idx>` fallback when
 * all four miss.
 *
 * Function indices count imported functions first, then code-section-defined
 * functions (per the WASM spec). All four sources speak this combined index
 * space.
 */
public class WasmSymbolResolver(
    private val provider: WasmBinaryProvider,
    private val dwarfIndex: WasmDwarfNameIndex? = null,
) : SymbolResolver {

    private val funcExportNames: Map<Int, String> by lazy {
        provider.exports
            .filterIsInstance<WasmFunctionExportRecordEntry>()
            .associate { it.index.toInt() to it.name }
    }

    private val funcNames: Map<Int, String>? by lazy {
        provider.nameSection?.functionSubsection?.namemap
    }

    override fun resolve(kind: SymbolKind, index: Int): String? = when (kind) {
        SymbolKind.FUNCTION ->
            funcNames?.get(index)
                ?: dwarfIndex?.nameFor(index)
                ?: dwarfIndex?.linkageNameFor(index)
                ?: funcExportNames[index]
        // Other namespaces deferred until the WAT printer needs them.
        else -> null
    }
}
