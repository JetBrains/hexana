package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.model.elements.ElementSegmentResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.wat
import org.jetbrains.hexana.mcp.watString
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment

fun WasmBinaryProvider.readElementSegments(): ListElementSegmentsResult {
    val wasmModule = requireNotNull(wasmModule)
    val elementSection = wasmModule.elementSection ?: return ListElementSegmentsResult(emptyList())
    return with(elementSection) {
        ListElementSegmentsResult(
            segments = bytes.scanElements().mapIndexed { index, segment ->
                when (segment) {
                    is FuncIdxWasmElementSegment.ActiveDefault -> ElementSegmentResult(
                        elementidx = index,
                        kind = 0,
                        mode = "active",
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }
                    )

                    is FuncIdxWasmElementSegment.Passive -> ElementSegmentResult(
                        elementidx = index,
                        kind = 1,
                        mode = "passive",
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }
                    )

                    is FuncIdxWasmElementSegment.ActiveExplicit -> ElementSegmentResult(
                        elementidx = index,
                        kind = 2,
                        mode = "active",
                        tableIdx = segment.tableIndex.toInt(),
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }
                    )

                    is FuncIdxWasmElementSegment.Declare -> ElementSegmentResult(
                        elementidx = index,
                        kind = 3,
                        mode = "declarative",
                        elementType = "funcref",
                        elements = segment.funcs.map { it.toInt().toString() }
                    )

                    is ExprWasmElementSegment.ActiveDefault -> ElementSegmentResult(
                        elementidx = index,
                        kind = 4,
                        mode = "active",
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }
                    )

                    is ExprWasmElementSegment.Passive -> ElementSegmentResult(
                        elementidx = index,
                        kind = 5,
                        mode = "passive",
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }
                    )

                    is ExprWasmElementSegment.ActiveExplicit -> ElementSegmentResult(
                        elementidx = index,
                        kind = 6,
                        mode = "active",
                        tableIdx = segment.tableIndex.toInt(),
                        offsetExpr = segment.offsetExpr.map { it.watString().toString() },
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }
                    )

                    is ExprWasmElementSegment.Declare -> ElementSegmentResult(
                        elementidx = index,
                        kind = 7,
                        mode = "declarative",
                        elementType = "funcref",
                        elements = segment.exprs.map { it.wat() }
                    )
                }
            }
        )
    }
}
