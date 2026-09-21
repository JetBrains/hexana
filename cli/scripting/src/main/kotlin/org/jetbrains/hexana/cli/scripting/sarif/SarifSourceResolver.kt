package org.jetbrains.hexana.cli.scripting.sarif

import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.BinaryInputLoader
import org.jetbrains.hexana.mcp.model.functions.ResolvedFunctionSymbolResult
import org.jetbrains.hexana.mcp.query.resolveFunctionSymbols
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider

/** Symbol source tag emitted by the crash resolver when no real name was found. */
private const val SYMBOL_FUNCIDX_FALLBACK = "funcidx_fallback"

/**
 * Resolves function names and source locations for SARIF output by delegating to the
 * repo's production resolution machinery. No hand-rolled section walking.
 *
 * Machinery used (file:symbol):
 * - Name chain (import > name section > DWARF name > DWARF linkage name > export):
 *   [resolveFunctionSymbols] in mcp/core CrashResolverQuery.kt. When the chain bottoms
 *   out at its funcidx fallback, [functionName] returns null so the SARIF renderer
 *   applies its own "func[<idx>]" fallback.
 * - Source locations: [WasmBinaryProvider.sourceLocationProvider]
 *   (binaryProvider DwarfSourceLocationProvider), queried per function via
 *   `getFirstSourceLocationInRange` over the function's code range - the point query
 *   at a body start misses because the size field and locals precede the first
 *   line-program row. The provider merges a CU-scoped pass with a line-table-only
 *   pass, so compilation units that use DW_AT_ranges (clang/wasi-sdk, rustc) resolve.
 *   Binaries post-processed by binaryen wasm-opt resolve to null: wasm-opt drops all
 *   .debug_line program rows, leaving prologue-only units with no address-to-line data.
 *
 * Per-funcidx results are memoized: each [resolveFunctionSymbols] call builds a fresh
 * resolver context, and the same funcidx is typically queried twice (name + location).
 *
 * Non-wasm providers: [from] returns null and the CLI omits source enrichment.
 */
class SarifSourceResolver(private val provider: WasmBinaryProvider) {

    private val cache = HashMap<Int, ResolvedFunctionSymbolResult>()

    private fun resolved(funcidx: Long): ResolvedFunctionSymbolResult? {
        if (funcidx < 0 || funcidx > Int.MAX_VALUE.toLong()) return null
        val idx = funcidx.toInt()
        return cache.getOrPut(idx) {
            provider.resolveFunctionSymbols(listOf(idx)).symbols.first()
        }
    }

    /**
     * Returns the best available symbol name for [funcidx] (import, name section,
     * DWARF name, DWARF linkage name, or export name), or null when only the
     * funcidx fallback is available.
     */
    fun functionName(funcidx: Long): String? {
        val symbol = resolved(funcidx) ?: return null
        return symbol.symbol.takeUnless { symbol.symbolSource == SYMBOL_FUNCIDX_FALLBACK }
    }

    /**
     * Returns the first mapped source location inside the function's code range,
     * or null (imports, invalid indices, stripped binaries, unmapped functions).
     */
    fun sourceLocation(funcidx: Long): SarifSourceLocation? {
        val range = resolved(funcidx)?.codeRange ?: return null
        val start = range.offset
        val end = range.offset + range.size
        if (start < 0 || end > Int.MAX_VALUE.toLong()) return null
        val loc = provider.sourceLocationProvider
            .getFirstSourceLocationInRange(start.toInt(), end.toInt()) ?: return null
        return SarifSourceLocation(uri = loc.filePath, startLine = loc.line)
    }

    companion object {
        /** Returns a resolver for wasm providers, null for any other provider type. */
        fun from(provider: BinaryProvider): SarifSourceResolver? =
            (provider as? WasmBinaryProvider)?.let(::SarifSourceResolver)

        /**
         * Convenience for tests and byte-level callers: loads [bytes] through the same
         * [BinaryInputLoader] path the CLI uses and wraps the resulting provider.
         * Returns null when the bytes do not load as a core wasm module.
         */
        fun from(bytes: ByteArray): SarifSourceResolver? =
            runCatching { BinaryInputLoader().load(BinaryInput.RawBytes(bytes)).provider }
                .getOrNull()
                ?.let(::from)
    }
}
