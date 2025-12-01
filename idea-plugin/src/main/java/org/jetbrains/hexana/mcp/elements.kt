package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment

fun CommonByteBuffer.listElementSegments(wasmModule: WasmModule?): List<Element> {
    wasmModule ?: return emptyList()
    wasmModule.elementSection ?: return emptyList()
    val elements = mutableListOf<Element>()
    with(wasmModule.elementSection!!) {
        scanElements().mapIndexed { index, segment ->
            when(segment) {
                is FuncIdxWasmElementSegment.ActiveDefault -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 0,
                        mode = "active",
                        tableIdx = null,
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }))
                is FuncIdxWasmElementSegment.Passive -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 1,
                        mode = "passive",
                        tableIdx = null,
                        offsetExpr = null,
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }))
                is FuncIdxWasmElementSegment.ActiveExplicit -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 2,
                        mode = "active",
                        tableIdx = segment.tableIndex.toInt(),
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }))
                is FuncIdxWasmElementSegment.Declare -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 3,
                        mode = "declarative",
                        tableIdx = null,
                        offsetExpr = null,
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }))

                is ExprWasmElementSegment.ActiveDefault -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 4,
                        mode = "active",
                        tableIdx = null,
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }))
                is ExprWasmElementSegment.Passive -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 5,
                        mode = "passive",
                        tableIdx = null,
                        offsetExpr = null,
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }))
                is ExprWasmElementSegment.ActiveExplicit -> elements.add(
                    Element(
                        elementidx = index,
                        kind = 6,
                        mode = "active",
                        tableIdx = segment.tableIndex.toInt(),
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }))
                is ExprWasmElementSegment.Declare ->elements.add(
                    Element(
                        elementidx = index,
                        kind = 7,
                        mode = "declarative",
                        tableIdx = null,
                        offsetExpr = null,
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }))
            }

        }
    }
    return elements
}

class Element(
    val elementidx: Int,
    val kind: Int,
    val mode: String,
    val tableIdx: Int?,
    val offsetExpr: List<String>?,
    val elementType: String,
    val elements: List<String>?
)