package org.jetbrains.hexana.run

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

internal object WasmDwarfUtil {

    private val DWARF_SECTION_NAMES = setOf(
        ".debug_info",
        ".debug_line",
        ".debug_abbrev",
        ".debug_str",
    )

    /**
     * Returns `true` when the given Wasm binary contains at least a
     * `.debug_info` custom section, which indicates that DWARF debug
     * metadata is embedded and a native debugger can use it.
     */
    fun hasDwarfInfo(wasmBytes: ByteArray): Boolean {
        val module = try {
            val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
            if (!provider.canProvideWasmModule()) return false
            provider.wasmModule
        } catch (_: Exception) {
            return false
        } ?: return false
        return module.customSections.any { it.name == ".debug_info" }
    }

    /**
     * Returns the set of DWARF custom section names present in the binary
     * (e.g. `.debug_info`, `.debug_line`).
     */
    fun dwarfSectionNames(wasmBytes: ByteArray): Set<String> {
        val module = try {
            val provider = WasmBinaryProvider(wasmBytes.toCommonByteBuffer())
            if (!provider.canProvideWasmModule()) return emptySet()
            provider.wasmModule
        } catch (_: Exception) {
            return emptySet()
        } ?: return emptySet()
        return module.customSections
            .map { it.name }
            .filter { it in DWARF_SECTION_NAMES }
            .toSet()
    }
}
