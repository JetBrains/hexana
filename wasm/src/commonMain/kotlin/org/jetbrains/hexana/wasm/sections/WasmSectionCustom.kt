package org.jetbrains.hexana.wasm.sections

class WasmSectionCustom(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 0
    }
}
