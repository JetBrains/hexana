package org.jetbrains.hexana.wasm.sections

class WasmSectionCustom(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int):
    WasmSection("Custom", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 0
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

}
