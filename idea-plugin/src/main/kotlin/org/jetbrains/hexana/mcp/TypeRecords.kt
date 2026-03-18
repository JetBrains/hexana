package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord

// TODO move this helper into TypesQuery.kt after the remaining legacy callers
// are migrated out of utils.kt, then remove this temporary shared file.
internal fun CommonByteBuffer.types(wasmModule: WasmModule): List<WasmTypeRecord> =
    with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        parseTypes()
    }
