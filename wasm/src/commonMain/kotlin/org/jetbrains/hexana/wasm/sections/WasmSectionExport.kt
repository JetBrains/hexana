package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.CommonByteBuffer

class WasmSectionExport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 7
    }

    fun CommonByteBuffer.scan() {
        position(offset)

    }
}
