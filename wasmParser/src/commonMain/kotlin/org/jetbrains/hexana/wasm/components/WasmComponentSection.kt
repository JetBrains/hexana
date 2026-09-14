package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.containsRange
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
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

    fun CommonByteBuffer.parseNestedComponent(): WasmComponent? {
        if (contentSize <= 0 || !containsRange(contentOffset, contentSize)) return null
        position(contentOffset)
        val nestedBytes = ByteArray(contentSize)
        get(nestedBytes, 0, contentSize)
        val nestedBuffer = nestedBytes.toCommonByteBuffer()
        val parseResult = WasmParser().parse(nestedBuffer)
        return (parseResult as? WasmParseSuccess)?.wasmModule as? WasmComponent
    }
}
