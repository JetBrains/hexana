package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.sections.*

/**
 * Resolves a function name from the name section, returning an empty string when unavailable.
 */
fun resolveFunctionName(
    functionNamesMap: Map<Int, String>?,
    functionIndex: Int,
): String = functionNamesMap?.get(functionIndex) ?: ""

/**
 * Formats a [WasmTypeRecord] into a readable signature/description string.
 * Uses the optional [typeNames] map to resolve reference type names.
 */
fun WasmTypeRecord.getDescription(index: Int, typeNames: Map<Int, String>? = null): String {
    return when (this) {
        is WasmSectionFunctionTypeRecord -> "(${(this.params.map { it.getDescription(typeNames) }).joinToString(", ")}) -> " + (if (this.retTypes.isEmpty()) "void" else this.retTypes.joinToString(", ") { it.getDescription(typeNames) })
        is WasmStructTypeRecord -> {
            val name = typeNames?.get(index)?.let { "struct $it" }
            name ?: "struct#$index"
        }
        is WasmRecursiveTypeRecord -> {
            val name = typeNames?.get(index)?.let { "recursive $it" }
            name ?: "recursive#$index"
        }
        else -> toString()
    }
}

/**
 * Formats a single [WasmValueType], resolving reference types via [typeNames] when available.
 */
fun WasmValueType<*>.getDescription(typeNames: Map<Int, String>? = null): String {
    return when (this) {
        WasmI32Type -> "i32"
        WasmF32Type -> "f32"
        WasmI64Type -> "i64"
        WasmF64Type -> "f64"
        is WasmVecType -> "v128"
        is WasmRefType -> {
            val type = this.code.heapType.type.toInt()
            val name = typeNames?.get(type)
            val nullabilityIndicator = when (code.heapType) {
                is WasmNullableReferenceType -> "?"
                else -> ""
            }
            (name ?: "ref(${code.heapType.type})") + nullabilityIndicator
        }
        else -> toString()
    }
}
