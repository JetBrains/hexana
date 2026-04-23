package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.Limits
import org.jetbrains.hexana.mcp.MemoryRecord
import org.jetbrains.hexana.mcp.listMemory
import org.jetbrains.hexana.mcp.listMemoryByIndices
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.memory.MemoryLimitsResult
import org.jetbrains.hexana.mcp.model.memory.MemoryResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readMemory(): ListMemoryResult {
    val wasmModule = requireNotNull(wasmModule)
    return ListMemoryResult(
        memories = bytes.listMemory(wasmModule).map { it.toMemoryResult() }
    )
}

fun WasmBinaryProvider.readMemoryByIndices(
    indices: List<Int>
): ListMemoryResult {
    val wasmModule = requireNotNull(wasmModule)
    return ListMemoryResult(
        memories = bytes.listMemoryByIndices(wasmModule, indices).map { it.toMemoryResult() }
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
