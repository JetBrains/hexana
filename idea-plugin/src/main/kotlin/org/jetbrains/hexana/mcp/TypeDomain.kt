package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.*

/**
 * Shared type-domain records and signature helpers used across MCP queries and
 * lower-level function extraction code.
 *
 * This file intentionally contains reusable Wasm type parsing/signature logic,
 * not serialized MCP response models.
 */
internal fun CommonByteBuffer.types(wasmModule: WasmModule): List<WasmTypeRecord> =
    with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        parseTypes()
    }

internal fun resolveFunctionTypeRecord(
    types: List<WasmTypeRecord>,
    typeIndex: Int
): WasmSectionFunctionTypeRecord? {
    if (typeIndex < 0) return null
    var flattenedIndex = 0
    for (record in types) {
        when (record) {
            is WasmSectionFunctionTypeRecord -> {
                if (flattenedIndex == typeIndex) return record
                flattenedIndex += 1
            }

            is WasmRecursiveTypeRecord -> {
                for (entry in record.entries) {
                    if (flattenedIndex == typeIndex) {
                        return entry as? WasmSectionFunctionTypeRecord
                    }
                    flattenedIndex += 1
                }
            }

            else -> {
                if (flattenedIndex == typeIndex) return null
                flattenedIndex += 1
            }
        }
    }
    return null
}

fun WasmTypeIndexExternalType.toSignature(types: List<WasmTypeRecord>): ParameterSignature? {
    val typeIndex = index.toInt()
    return resolveFunctionTypeRecord(types, typeIndex)?.toSignature()
}

fun WasmSectionFunctionTypeRecord.toSignature(): ParameterSignature {
    return ParameterSignature(
        params = params.map { it.toSignature() },
        ret = retTypes.map { it.toSignature() }
    )
}

fun WasmValueType<*>.toSignature(): String {
    return when (this) {
        is WasmF32Type -> "f32"
        is WasmF64Type -> "f64"
        is WasmI32Type -> "i32"
        is WasmI64Type -> "i64"
        is WasmPactTypeI8 -> "i8"
        is WasmPactTypeI16 -> "i16"
        else -> "unknown"
    }
}

data class ParameterSignature(
    val params: List<String>,
    val ret: List<String>
)
