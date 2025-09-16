package org.jetbrains.hexana.wasm.sections

class WasmSectionMemory(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 5
    }
}
