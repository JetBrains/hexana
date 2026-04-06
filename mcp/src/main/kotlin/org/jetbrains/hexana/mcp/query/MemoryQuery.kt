package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.Limits
import org.jetbrains.hexana.mcp.MemoryRecord
import org.jetbrains.hexana.mcp.listMemory
import org.jetbrains.hexana.mcp.listMemoryByIndices
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.memory.MemoryLimitsResult
import org.jetbrains.hexana.mcp.model.memory.MemoryResult
import org.jetbrains.hexana.wasm.WasmModule

fun CommonByteBuffer.readMemory(wasmModule: WasmModule): ListMemoryResult {
    return ListMemoryResult(
        memories = listMemory(wasmModule).map { it.toMemoryResult() }
    )
}

fun CommonByteBuffer.readMemoryByIndices(
    wasmModule: WasmModule,
    indices: List<Int>
): ListMemoryResult {
    return ListMemoryResult(
        memories = listMemoryByIndices(wasmModule, indices).map { it.toMemoryResult() }
    )
}

private fun MemoryRecord.toMemoryResult(): MemoryResult {
    return MemoryResult(
        memoryIdx = memoryIdx,
        limit = limit.toMemoryLimitsResult(),
        definedIn = definedIn
    )
}

private fun Limits<*>.toMemoryLimitsResult(): MemoryLimitsResult {
    return MemoryLimitsResult(
        addressType = addressType,
        min = requireNotNull(min).toLongValue(),
        max = max.toLongValueOrNull(),
        hasMax = hasMax,
        range = range,
        flag = flag
    )
}

private fun Any.toLongValue(): Long {
    return when (this) {
        is Int -> toLong()
        is Long -> this
        else -> error("Unknown numeric type: ${this::class.qualifiedName}")
    }
}

private fun Any?.toLongValueOrNull(): Long? {
    return this?.toLongValue()
}
