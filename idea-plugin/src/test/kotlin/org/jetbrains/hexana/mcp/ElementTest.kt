package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Ref
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmReferenceHeapType

class ElementTest : WasmTest() {
    fun testElement0() = doElementTest { elems ->
        assertEquals(1, elems.size)
        assertEquals(
            FuncIdxWasmElementSegment.ActiveDefault(
                offset = 28,
                size = 7,
                offsetExpr = listOf(OpCodes.I32.Const(0)),
                funcs = listOf(0u, 1u)
            ), elems[0]
        )
    }

    fun testElement1() = doElementTest { elems ->
        assertEquals(1, elems.size)
        assertEquals(
            ExprWasmElementSegment.Passive(
                offset = 22,
                size = 9,
                refType = WasmReferenceHeapType(WasmFuncHeapType),
                exprs = listOf(
                    listOf(Ref.Func(0u)),
                    listOf(Ref.Func(1u))
                )
            ),
            elems[0]
        )
    }

    fun testElement2() = doElementTest { elems ->
        assertEquals(1, elems.size)
        assertEquals(
            ExprWasmElementSegment.Passive(
                offset = 32,
                size = 9,
                refType = WasmReferenceHeapType(WasmFuncHeapType),
                exprs = listOf(
                    listOf(Ref.Func(0u)),
                    listOf(Ref.Func(1u))
                )
            ),
            elems[0]
        )
    }
}