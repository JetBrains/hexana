package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionElement(size: Int, offset: Int) : WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 9
    }

    fun CommonByteBuffer.scanElements(): List<WasmElementSegment> {
        position(offset)
        val elements = mutableListOf<WasmElementSegment>()
        consumeArray {
            val elementType = getULEB128().toInt()
            when (elementType) {
                0 -> {
                    val opcodes = parseOpCodes()
                    val funcidxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.ActiveDefault(opcodes, funcidxs))
                }

                1 -> {
                    val rt = get()
                    val funcindxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.Passive(funcindxs))
                }

                2 -> {
                    val tableindx = getULEB128().toUInt()
                    val exprOffset = parseOpCodes()
                    val rt = get()
                    val funcindxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.ActiveExplicit(tableindx,exprOffset, funcindxs))
                }

                3 -> {
                    val rt = get()
                    val funcindxs = consumeFuncidxs()
                    elements.add(FuncIdxWasmElementSegment.Declare(funcindxs))
                }

                4 -> {
                    val opcodes = parseOpCodes()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.ActiveDefault(opcodes, exprList))
                }
                5 -> {
                    val refType = referenceType()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.Passive(refType, exprList))
                }

                6 -> {
                    val tableindx = getULEB128().toUInt()
                    val expression = parseOpCodes()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.ActiveExplicit(tableindx, expression, exprList))
                }
                7 -> {
                    val refType = referenceType()
                    val exprList = consumeExpressionList()
                    elements.add(ExprWasmElementSegment.Declare(refType, exprList))
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


sealed interface WasmElementSegment

sealed class FuncIdxWasmElementSegment : WasmElementSegment {
    abstract val funcs: List<UInt>

    data class ActiveDefault(
        val offsetExpr: List<OpCode>,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class Passive(
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class ActiveExplicit(
        val tableIndex: UInt,
        val offsetExpr: List<OpCode>,
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()

    data class Declare(
        override val funcs: List<UInt>
    ) : FuncIdxWasmElementSegment()
}

sealed class ExprWasmElementSegment : WasmElementSegment {
    abstract val exprs: List<List<OpCode>>

    data class ActiveDefault(
        val offsetExpr: List<OpCode>,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class Passive(
        val refType: WasmReferenceType,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class ActiveExplicit(
        val tableIndex: UInt,
        val offsetExpr: List<OpCode>,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()

    data class Declare(
        val refType: WasmReferenceType,
        override val exprs: List<List<OpCode>>
    ) : ExprWasmElementSegment()
}
