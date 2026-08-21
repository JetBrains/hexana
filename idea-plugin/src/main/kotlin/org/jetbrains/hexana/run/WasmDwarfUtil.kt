package org.jetbrains.hexana.run

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

internal object WasmDwarfUtil {

    /**
     * Returns `true` when the given Wasm binary contains at least a
     * `.debug_info` custom section, which indicates that DWARF debug
     * metadata is embedded and a native debugger can use it.
     *
     * For component model binaries, this checks the embedded core
     * modules for DWARF sections.
     */
    fun hasDwarfInfo(wasmBytes: ByteArray): Boolean {
        val buffer = wasmBytes.toCommonByteBuffer()
        return when (WasmBinaryProviderSelector.select(buffer)) {
            is BinaryProviderType.Wasm -> hasDwarfInCoreModule(buffer)
            is BinaryProviderType.WasmComponent -> hasDwarfInComponent(buffer)
            else -> false
        }
    }

    /**
     * Checks whether a core Wasm module contains a `.debug_info` custom section.
     */
    private fun hasDwarfInCoreModule(wasmBytes: CommonByteBuffer): Boolean {
        return try {
            val provider = WasmBinaryProvider(wasmBytes)
            val module = provider.wasmModule
            module != null && module.customSections.any { it.name == ".debug_info" }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether a component binary contains DWARF `.debug_info`
     * sections, either at the top-level (component custom sections) or
     * inside any embedded core module.
     */
    private fun hasDwarfInComponent(wasmBytes: CommonByteBuffer): Boolean {
        try {
            val provider = WasmComponentBinaryProvider(wasmBytes)
            if (!provider.canProvideWasmModule()) return false

            // Check top-level component custom sections first
            val component = provider.wasmModule
            if (component != null && component.customSections.any { it.name == ".debug_info" }) {
                return true
            }

            // Then check embedded core modules
            return embeddedCoreModules(wasmBytes).any { hasDwarfInCoreModule(it) }
        } catch (_: Exception) {
            // Not a valid component or parsing failed
            return false
        }
    }

    /**
     * The bytes of every core module embedded in [wasmBytes], in `module` section
     * order — the same order the runtime assigns module ids in, so index `i` is the
     * module a gdbstub PC with module id `i` addresses.
     *
     * Each element is a standalone core module starting at its own magic, so it can
     * be handed to any core-module parser and its offsets line up with the ones the
     * stub reports. Empty when [wasmBytes] is not a parseable component.
     */
    fun embeddedCoreModules(wasmBytes: CommonByteBuffer): List<CommonByteBuffer> {
        return try {
            val buffer = wasmBytes
            val provider = WasmComponentBinaryProvider(buffer)
            if (!provider.canProvideWasmModule()) return emptyList()
            provider.modules.map { coreModule ->
                val moduleBytes = ByteArray(coreModule.contentSize)
                buffer.position(coreModule.contentOffset)
                buffer.get(moduleBytes, 0, coreModule.contentSize)
                moduleBytes.toCommonByteBuffer()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
