package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange


open class WasmSection(
    val name: String,
    val sectionSize: Int,
    val sectionOffset: Int,
    val contentSize: Int,
    val contentOffset: Int,
) {
    val sectionRange: IntRange get() = sectionOffset until sectionOffset + sectionSize
    val contentRange: IntRange get() = contentOffset until contentOffset + contentSize

    open fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return emptyList()
    }

    protected fun instructionReadingLimit(): Int = sectionOffset + sectionSize
}

class WasmSectionUnresolved(
    val id: Byte,
    sectionSize: Int,
    sectionOffset: Int,
    contentSize: Int,
    contentOffset: Int
): WasmSection("Unknown", sectionSize, sectionOffset, contentSize, contentOffset)

fun CommonByteBuffer.consumeString(): String {
    val nameSize = getULEB128().toInt()
    // A string of nameSize bytes needs at least nameSize bytes remaining; reject
    // negative sizes (LEB128 overflow) and sizes bigger than the buffer could ever
    // hold, instead of attempting a huge/negative ByteArray allocation up front.
    if (nameSize < 0 || nameSize > remaining()) {
        throw WasmParseException("Invalid string length $nameSize exceeds remaining buffer bytes (${remaining()})")
    }
    val nameBytes = ByteArray(nameSize)
    get(nameBytes, 0, nameSize)
    return nameBytes.decodeToString()
}

internal fun CommonByteBuffer.consumeArray(block: (Int) -> Unit) {
    val vecCount = getULEB128().toInt()
    // Every vector element consumes at least one byte, so a vector of vecCount
    // elements needs at least vecCount bytes remaining. Reject counts that can't
    // possibly fit before repeat() drives billions of iterations/allocations.
    if (vecCount < 0 || vecCount > remaining()) {
        throw WasmParseException("Invalid vector count $vecCount exceeds remaining buffer bytes (${remaining()})")
    }
    repeat(vecCount, block)
}

internal fun CommonByteBuffer.sizeFromOffset(offset: Int): Int = position() - offset
