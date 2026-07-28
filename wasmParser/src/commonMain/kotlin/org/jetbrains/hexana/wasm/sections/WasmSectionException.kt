package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * The Tag section (id 13, "Exceptions" here). Declares the module's defined
 * exception tags. Each tag entry is an attribute byte (0x00 = exception) plus
 * a `typeidx` into the type section giving the tag's parameter signature; the
 * result types are always empty for exception tags. Imported tags precede
 * these in the tag index space (import section, external kind 0x04).
 */
class WasmSectionException(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Exceptions", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 13
    }

    constructor(s: WasmSectionUnresolved) : this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    /** Per-entry byte range + decoded (attribute, typeidx) for each defined tag. */
    fun CommonByteBuffer.scanTags(): List<WasmTagRecord> {
        position(contentOffset)
        val count = getULEB128().toInt()
        val out = ArrayList<WasmTagRecord>(count)
        repeat(count) {
            val start = position()
            val attribute = get().toInt() and 0xFF
            val typeIdx = getULEB128().toInt()
            out.add(WasmTagRecord(offset = start, size = position() - start, attribute = attribute, typeIdx = typeIdx))
        }
        return out
    }
}

data class WasmTagRecord(val offset: Int, val size: Int, val attribute: Int, val typeIdx: Int)
