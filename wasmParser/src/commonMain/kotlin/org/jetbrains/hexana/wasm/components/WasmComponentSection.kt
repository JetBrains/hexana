package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved

class WasmComponentSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("Component", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
    ) {
    companion object {
        const val SECTION_ID: Byte = 4
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)
}