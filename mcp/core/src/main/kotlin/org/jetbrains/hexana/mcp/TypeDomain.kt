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

// see https://webassembly.github.io/spec/core/text/types.html
fun StandardTypesParser.WasmBlockType.toSignature(): String {
    return when (this) {
        is StandardTypesParser.WasmBlockType.Empty -> "void"
        is WasmValueType<*> -> toSignature()
        is StandardTypesParser.WasmBlockType.S33 -> "S33"
        else -> "BlockType (${this::class.simpleName})"
    }
}

fun WasmValueType<*>.toSignature(): String {
    // Note: Do not insert there just else. This will be refactored soon, we shouldn't have branches with types and unresolved text.
    return when (this) {
        is WasmF32Type -> "f32"
        is WasmF64Type -> "f64"
        is WasmI32Type -> "i32"
        is WasmI64Type -> "i64"
        is WasmVecType -> "v128"
        is WasmPactTypeI8 -> "i8"
        is WasmPactTypeI16 -> "i16"
        is WasmRefType -> code.toSignature()
    }
}

internal fun WasmReferenceType.toSignature(): String =
    // Note: Do not insert there just else. This will be refactored soon, we shouldn't have branches with types and unresolved text.
    when (this) {
        is WasmNullableReferenceType -> heapType.toNullableSignature()
        is WasmNonNullableReferenceType -> "ref ${heapType.toHeapTypeName()}"
        // Binary shorthand abstract heap types like `extern` and `func` mean `ref null extern`
        // and `ref null func`, so they should render via the nullable shorthand form.
        is WasmReferenceHeapType -> heapType.toNullableSignature()
    }

// see https://webassembly.github.io/spec/core/text/types.html
// [WasmNoExnHeapType] and [WasmIndexHeapType] not defined
internal fun WasmHeapType.toNullableSignature(): String =
    // Note: Do not insert there just else. This will be refactored soon, we shouldn't have branches with types and unresolved text.
    when (this) {
        is WasmExternRefHeapType -> "externref"
        is WasmFuncHeapType -> "funcref"
        is WasmAnyRefHeapType -> "anyref"
        is WasmEqRefHeapType -> "eqref"
        is WasmI31RefHeapType -> "i31ref"
        is WasmStructRefHeapType -> "structref"
        is WasmArrayRefHeapType -> "arrayref"
        is WasmExnRefHeapType -> "exnref"
        is WasmNoneHeapType -> "nullref"
        is WasmNoExternRefHeapType -> "nullexternref"
        is WasmNoFuncHeapType -> "nullfuncref"
        is WasmNoExnHeapType -> "nullexnref"
        is WasmIndexHeapType -> "nullreftype[$type]"
        is WasmExactHeapType -> "exact[$type]"
    }

// see https://webassembly.github.io/spec/core/text/types.html
internal fun WasmHeapType.toHeapTypeName(): String =
    // Note: Do not insert there just else. This will be refactored soon, we shouldn't have branches with types and unresolved text.
    when (this) {
        is WasmExternRefHeapType -> "extern"
        is WasmFuncHeapType -> "func"
        is WasmAnyRefHeapType -> "any"
        is WasmEqRefHeapType -> "eq"
        is WasmI31RefHeapType -> "i31"
        is WasmStructRefHeapType -> "struct"
        is WasmArrayRefHeapType -> "array"
        is WasmExnRefHeapType -> "exn"
        is WasmNoneHeapType -> "none"
        is WasmNoExternRefHeapType -> "noextern"
        is WasmNoFuncHeapType -> "nofunc"
        is WasmNoExnHeapType -> "noexn"
        is WasmIndexHeapType -> "type[$type]"
        is WasmExactHeapType -> "exact[$type]"
    }

data class ParameterSignature(
    val params: List<String>,
    val ret: List<String>
)
