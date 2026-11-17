package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.boundedDuplicate
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

fun CommonByteBuffer.consumeString(): String = consumeStringWithin(limit())

internal fun CommonByteBuffer.consumeStringWithin(endOffset: Int): String {
    val boundary = minOf(endOffset, limit())
    if (boundary < position()) throw WasmParseException("Invalid string boundary")
    val nameSize = consumeUnsignedInt(boundary)
    // A string of nameSize bytes needs at least nameSize bytes remaining before
    // allocating its backing array.
    if (nameSize > boundary - position()) {
        throw WasmParseException("Invalid string length $nameSize exceeds remaining section bytes (${boundary - position()})")
    }
    val nameBytes = ByteArray(nameSize)
    get(nameBytes, 0, nameSize)
    return nameBytes.decodeToString()
}

internal fun CommonByteBuffer.consumeArray(endOffset: Int = limit(), block: CommonByteBuffer.(Int) -> Unit) {
    val source = this
    val boundary = minOf(endOffset, limit())
    if (boundary < source.position()) throw WasmParseException("Invalid array boundary")
    val view = source.boundedDuplicate(boundary)
    try {
        val countStart = view.position()
        val count = view.consumeUnsignedInt(boundary)
        if (count > boundary - view.position()) throw WasmParseException("Vector count exceeds its boundary at $countStart")
        repeat(count) { index ->
            if (view.position() >= boundary) throw WasmParseException("Vector entry exceeds its boundary")
            try {
                view.block(index)
            } catch (e: Exception) {
                if (e.isBoundedViewExhaustion()) {
                    throw WasmParseException("Vector entry exceeds its boundary")
                }
                throw e
            }
        }
    } finally {
        source.position(view.position())
    }
}

private fun Exception.isBoundedViewExhaustion(): Boolean =
    this is IndexOutOfBoundsException || this::class.simpleName == "BufferUnderflowException"

internal data class PartialArray<T>(val values: MutableList<T>, val failureOffset: Int?)

internal fun <T> CommonByteBuffer.consumeArrayPartial(endOffset: Int, block: CommonByteBuffer.(Int) -> T): PartialArray<T> {
    val source = this
    val countStart = position()
    val boundary = minOf(endOffset, limit())
    if (boundary < countStart) return PartialArray(mutableListOf(), countStart)
    val view = boundedDuplicate(boundary)
    try {
        val count = try {
            view.consumeUnsignedInt(boundary)
        } catch (_: Exception) {
            return PartialArray(mutableListOf(), countStart)
        }
        val values = mutableListOf<T>()
        repeat(count) { index ->
            val entryStart = view.position()
            if (entryStart >= boundary) return PartialArray(values, entryStart)
            try {
                values.add(view.block(index))
            } catch (_: Exception) {
                return PartialArray(values, entryStart)
            }
        }
        return PartialArray(values, null)
    } finally {
        source.position(view.position())
    }
}

internal fun CommonByteBuffer.consumeUnsignedUInt(endOffset: Int = limit()): UInt {
    val start = position()
    if (endOffset < start || endOffset > limit()) throw WasmParseException("Invalid u32 boundary")
    var value = 0L
    for (byteIndex in 0 until 5) {
        if (position() >= endOffset) throw WasmParseException("Truncated u32 at $start")
        val byte = get().toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl (byteIndex * 7))
        if (byte and 0x80 == 0) {
            if (byteIndex == 4 && byte and 0x70 != 0) {
                throw WasmParseException("Invalid u32 at $start")
            }
            return value.toUInt()
        }
    }
    throw WasmParseException("Invalid u32 at $start")
}

internal fun CommonByteBuffer.consumeUnsignedInt(endOffset: Int = limit()): Int {
    val start = position()
    val value = consumeUnsignedUInt(endOffset)
    if (value > Int.MAX_VALUE.toUInt()) throw WasmParseException("Invalid u32 at $start")
    return value.toInt()
}

internal fun CommonByteBuffer.sizeFromOffset(offset: Int): Int = position() - offset
