package org.jetbrains.hexana.wasm.sections

class WasmSectionStart(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection(sectionSize, sectionOffset, contentSize, contentOffset) {
    companion object {
        const val SECTION_ID: Byte = 8
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)
}
