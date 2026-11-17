package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.dwarf.DwarfSubprogramInfo
import org.jetbrains.hexana.dwarf.DwarfVariableInfoExtractor
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor

/**
 * `funcIdx → DwarfSubprogramInfo` lookup. Bridges the gap that `DwarfIndex`
 * doesn't cover: DWARF subprogram entries are keyed by `lowPc` (a code-section-
 * relative byte offset in WASM DWARF), not by function index. This index walks
 * subprograms once, correlates each `lowPc` against `WasmBinaryProvider.codeOffsets`
 * via byte-range containment, and produces the missing map.
 *
 * Functions are looked up by their **global** funcIdx (i.e. counting imported
 * functions first, then code-section-defined functions), matching the
 * convention used everywhere else in Hexana.
 *
 * Lazy: nothing is parsed until first query. Empty map if the binary has no
 * `.debug_info` section.
 */
public class WasmDwarfNameIndex(
    private val provider: WasmBinaryProvider,
) {

    private val byFuncIdx: Map<Int, DwarfSubprogramInfo> by lazy { build() }

    public fun nameFor(funcIdx: Int): String? =
        byFuncIdx[funcIdx]?.name?.takeIf { it.isNotEmpty() }

    public fun linkageNameFor(funcIdx: Int): String? =
        byFuncIdx[funcIdx]?.linkageName?.takeIf { it.isNotEmpty() }

    public fun subprogram(funcIdx: Int): DwarfSubprogramInfo? = byFuncIdx[funcIdx]

    private fun build(): Map<Int, DwarfSubprogramInfo> {
        val module = provider.wasmModule ?: return emptyMap()
        val codeSection = module.codeSection ?: return emptyMap()
        val codeOffsets = provider.codeOffsets
        if (codeOffsets.isEmpty()) return emptyMap()

        val subprograms = try {
            val accessor = WasmDwarfAccessor(module)
            with(DwarfVariableInfoExtractor(accessor)) {
                provider.bytes.extractSubprograms()
            }
        } catch (_: Throwable) {
            // Malformed/missing DWARF: fall back to empty index. Other naming
            // sources still apply.
            return emptyMap()
        }
        if (subprograms.isEmpty()) return emptyMap()

        val importFuncCount = provider.importFuncCount
        val sectionContentOffset = codeSection.contentOffset
        val out = HashMap<Int, DwarfSubprogramInfo>(subprograms.size)

        for (sub in subprograms) {
            val lowPc = sub.lowPc ?: continue
            val absolute = lowPc + sectionContentOffset
            val codeIdx = findCodeEntryContaining(codeOffsets, absolute)
            if (codeIdx >= 0) {
                out[importFuncCount + codeIdx] = sub
            }
        }
        return out
    }

    private fun findCodeEntryContaining(
        codeOffsets: List<org.jetbrains.hexana.wasm.sections.CodeEntryOffset>,
        absolute: Int,
    ): Int {
        // Binary search by byte range. Code entries are non-overlapping and
        // sorted by offset.
        var lo = 0
        var hi = codeOffsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entry = codeOffsets[mid]
            when {
                absolute < entry.offset -> hi = mid - 1
                absolute >= entry.offset + entry.size -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }
}
