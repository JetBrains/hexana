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

    /**
     * Parses as many tag entries as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that
     * entry (null second means the section parsed fully).
     */
    fun CommonByteBuffer.scanTagsPartial(): Pair<MutableList<WasmTagRecord>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) {
            val start = position()
            val attribute = get().toInt() and 0xFF
            val typeIdx = consumeUnsignedInt()
            WasmTagRecord(offset = start, size = position() - start, attribute = attribute, typeIdx = typeIdx)
        }.let { it.values to it.failureOffset }
    }

    /** Per-entry byte range + decoded (attribute, typeidx) for each defined tag. */
    fun CommonByteBuffer.scanTags(): List<WasmTagRecord> = scanTagsPartial().first
}

data class WasmTagRecord(val offset: Int, val size: Int, val attribute: Int, val typeIdx: Int)
