package org.jetbrains.hexana.wasm.sections

class WasmSectionException(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Exceptions", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 13
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)
}
