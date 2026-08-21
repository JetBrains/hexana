package org.jetbrains.hexana.run

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

internal object WasmDwarfUtil {

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
