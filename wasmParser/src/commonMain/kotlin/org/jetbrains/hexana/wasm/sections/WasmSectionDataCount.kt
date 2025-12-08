package org.jetbrains.hexana.wasm.sections

class WasmSectionDataCount(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection(sectionSize, sectionOffset, contentSize, contentOffset) {
    companion object {
        const val SECTION_ID: Byte = 12
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)
}
