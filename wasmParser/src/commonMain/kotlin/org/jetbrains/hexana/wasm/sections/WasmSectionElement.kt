package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.referenceType

class WasmSectionElement(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Elements", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 9
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with(buffer) {
            scanElements().mapIndexed { index, segment ->
                WasmNamedRange(segment, "element $index")
            }
        }
    }

    /**
     * Parses as many element segments as possible. On the first undecodable entry,
     * stops and returns the partial list paired with the byte offset of that entry
     * start (null second means the section parsed fully). The entry-start offset is
     * captured before decoding the flags LEB128 so that the reported offset points
     * at the first byte of the corrupt entry regardless of its encoding form (0-7).
     */
    fun CommonByteBuffer.scanElementsPartial(): Pair<MutableList<WasmElementSegment>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) {
            val offset = position()
            when (consumeUnsignedInt()) {
                    0 -> {
                        val opcodes = parseOpCodes(instructionReadingLimit())
                        val funcidxs = consumeFuncidxs()
                        FuncIdxWasmElementSegment.ActiveDefault(offset, sizeFromOffset(offset), opcodes, funcidxs)
                    }
                    1 -> {
                        val elementKind = get().toUByte()
                        val funcindxs = consumeFuncidxs()
                        FuncIdxWasmElementSegment.Passive(offset, sizeFromOffset(offset), elementKind, funcindxs)
                    }
                    2 -> {
                        val tableindx = consumeUnsignedUInt()
                        val exprOffset = parseOpCodes(instructionReadingLimit())
                        val elementKind = get().toUByte()
                        val funcindxs = consumeFuncidxs()
                        FuncIdxWasmElementSegment.ActiveExplicit(offset, sizeFromOffset(offset), elementKind, tableindx, exprOffset, funcindxs)
                    }
                    3 -> {
                        val elementKind = get().toUByte()
                        val funcindxs = consumeFuncidxs()
                        FuncIdxWasmElementSegment.Declare(offset, sizeFromOffset(offset), elementKind, funcindxs)
                    }
                    4 -> {
                        val opcodes = parseOpCodes(instructionReadingLimit())
                        val exprList = consumeExpressionList()
                        ExprWasmElementSegment.ActiveDefault(offset, sizeFromOffset(offset), opcodes, exprList)
                    }
                    5 -> {
                        val refType = referenceType()!!
                        val exprList = consumeExpressionList()
                        ExprWasmElementSegment.Passive(offset, sizeFromOffset(offset), refType, exprList)
                    }
                    6 -> {
                        val tableindx = consumeUnsignedUInt()
                        val expression = parseOpCodes(instructionReadingLimit())
                        val refType = referenceType()!!
                        val exprList = consumeExpressionList()
                        ExprWasmElementSegment.ActiveExplicit(offset, sizeFromOffset(offset), tableindx, expression, refType, exprList)
                    }
                    7 -> {
                        val refType = referenceType()!!
                        val exprList = consumeExpressionList()
                        ExprWasmElementSegment.Declare(offset, sizeFromOffset(offset), refType, exprList)
                    }
                    else -> throw IllegalArgumentException("Unknown element segment type")
                }
        }.let { it.values to it.failureOffset }
    }

    fun CommonByteBuffer.scanElements(): List<WasmElementSegment> = scanElementsPartial().first

    private fun CommonByteBuffer.consumeExpressionList(): MutableList<List<OpCode>> {
        val exprList = mutableListOf<List<OpCode>>()
        consumeArray { exprList.add(parseOpCodes(instructionReadingLimit())) }
        return exprList
    }

    private fun CommonByteBuffer.consumeFuncidxs(): List<UInt> {
        val funcidxs = mutableListOf<UInt>()
        consumeArray {
            funcidxs.add(consumeUnsignedUInt())
        }
        return funcidxs.toList()
    }
}


sealed interface WasmElementSegment : WasmRange

sealed class FuncIdxWasmElementSegment : WasmElementSegment {
    abstract val funcs: List<UInt>

    data class ActiveDefault(
        override val offset: Int,
        override val size: Int,
        val offsetExpr: List<OpCode>,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class Passive(
        override val offset: Int,
        override val size: Int,
        val elementKind: UByte,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class ActiveExplicit(
        override val offset: Int,
        override val size: Int,
        val elementKind: UByte,
        val tableIndex: UInt,
        val offsetExpr: List<OpCode>,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class Declare(
        override val offset: Int,
        override val size: Int,
        val elementKind: UByte,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()
}

sealed class ExprWasmElementSegment : WasmElementSegment {
    abstract val exprs: List<List<OpCode>>

    data class ActiveDefault(
        override val offset: Int,
        override val size: Int,
        val offsetExpr: List<OpCode>,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class Passive(
        override val offset: Int,
        override val size: Int,
        val refType: WasmReferenceType,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class ActiveExplicit(
        override val offset: Int,
        override val size: Int,
        val tableIndex: UInt,
        val offsetExpr: List<OpCode>,
        val refType: WasmReferenceType,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class Declare(
        override val offset: Int,
        override val size: Int,
        val refType: WasmReferenceType,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()
}
