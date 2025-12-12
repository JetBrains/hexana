package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

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

    fun CommonByteBuffer.scanElements(): List<WasmElementSegment> {
        position(contentOffset)
        val elements = mutableListOf<WasmElementSegment>()
        consumeArray {
            val offset = position()
            val elementType = getULEB128().toInt()
            when (elementType) {
                0 -> {
                    val opcodes = parseOpCodes()
                    val funcidxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.ActiveDefault(offset, sizeFromOffset(offset), opcodes, funcidxs))
                }

                1 -> {
                    val rt = get()
                    val funcindxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.Passive(offset, sizeFromOffset(offset), funcindxs))
                }

                2 -> {
                    val tableindx = getULEB128().toUInt()
                    val exprOffset = parseOpCodes()
                    val rt = get()
                    val funcindxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.ActiveExplicit(offset, sizeFromOffset(offset), tableindx, exprOffset, funcindxs))
                }

                3 -> {
                    val rt = get()
                    val funcindxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.Declare(offset, sizeFromOffset(offset), funcindxs))
                }

                4 -> {
                    val opcodes = parseOpCodes()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.ActiveDefault(offset, sizeFromOffset(offset), opcodes, exprList))
                }
                5 -> {
                    val refType = referenceType()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.Passive(offset, sizeFromOffset(offset), refType, exprList))
                }

                6 -> {
                    val tableindx = getULEB128().toUInt()
                    val expression = parseOpCodes()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.ActiveExplicit(offset, sizeFromOffset(offset), tableindx, expression, exprList))
                }
                7 -> {
                    val refType = referenceType()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.Declare(offset, sizeFromOffset(offset), refType, exprList))
                }
            }
        }
        return elements
    }

    private fun CommonByteBuffer.consumeExpressionList(): MutableList<List<OpCode>> {
        val exprList = mutableListOf<List<OpCode>>()
        consumeArray { exprList.add(parseOpCodes()) }
        return exprList
    }

    private fun CommonByteBuffer.consumeFuncidxs(): List<UInt> {
        val funcidxs = mutableListOf<UInt>()
        consumeArray {
            funcidxs.add(getULEB128().toUInt())
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
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class ActiveExplicit(
        override val offset: Int,
        override val size: Int,
        val tableIndex: UInt,
        val offsetExpr: List<OpCode>,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class Declare(
        override val offset: Int,
        override val size: Int,
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
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class Declare(
        override val offset: Int,
        override val size: Int,
        val refType: WasmReferenceType,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()
}
