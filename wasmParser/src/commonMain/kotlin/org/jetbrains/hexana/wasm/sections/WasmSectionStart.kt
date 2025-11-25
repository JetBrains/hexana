package org.jetbrains.hexana.wasm.sections

class WasmSectionStart(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 8
    }
}
