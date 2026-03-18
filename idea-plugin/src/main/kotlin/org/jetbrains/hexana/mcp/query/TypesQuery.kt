package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.model.types.TypeFieldResult
import org.jetbrains.hexana.mcp.model.types.TypeResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.jetbrains.hexana.mcp.types
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmAnyRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmEqRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExnRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmFieldType
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmI31RefHeapType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmIndexHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExnHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmNonNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNoneHeapType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmReferenceHeapType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType

internal fun CommonByteBuffer.readTypes(wasmModule: WasmModule): TypesResult {
    return TypesResult(
        types = types(wasmModule).mapIndexed { index, record -> record.toTypeResult(index) }
    )
}

internal fun CommonByteBuffer.readTypesByIndices(
    wasmModule: WasmModule,
    indices: List<Int>
): TypesResult {
    return TypesResult(
        types = types(wasmModule).mapIndexedNotNull { index, record ->
            if (indices.contains(index)) {
                record.toTypeResult(index)
            } else {
                null
            }
        }
    )
}

private const val MAX_TYPE_RENDER_DEPTH = 100

private fun WasmTypeRecord.toTypeResult(index: Int): TypeResult = when (this) {
    is WasmArrayTypeRecord -> TypeResult(
        kind = "array",
        typeidx = index,
        elementType = fieldType.toTypeFieldResult()
    )

    is WasmStructTypeRecord -> TypeResult(
        kind = "struct",
        typeidx = index,
        superTypes = superTypes,
        fields = fields.map { it.toTypeFieldResult() }
    )

    is WasmRecursiveTypeRecord -> TypeResult(
        kind = "rec",
        typeidx = index,
        entries = entries.map { it.toTypeString() }
    )

    is WasmSectionFunctionTypeRecord -> TypeResult(
        kind = "func",
        typeidx = index,
        params = params.map { it.toTypeString() },
        retTypes = retTypes.map { it.toTypeString() }
    )
}

private fun WasmFieldType.toTypeFieldResult(): TypeFieldResult {
    return TypeFieldResult(
        type = storageType.toTypeString(),
        mutable = isMutable
    )
}

internal fun WasmTypeRecord.toTypeString(depth: Int = 0): String {
    if (depth >= MAX_TYPE_RENDER_DEPTH) {
        return "..."
    }
    return when (this) {
        is WasmArrayTypeRecord -> "(array ${fieldType.toFieldDeclarationString(depth + 1)})"
        is WasmStructTypeRecord -> buildString {
            append("(struct")
            if (fields.isNotEmpty()) {
                append(' ')
                append(fields.joinToString(" ") { it.toFieldString(depth + 1) })
            }
            append(')')
        }
        is WasmRecursiveTypeRecord -> buildString {
            append("(rec")
            if (entries.isNotEmpty()) {
                append(' ')
                append(entries.joinToString(" ") { "(type ${it.toTypeString(depth + 1)})" })
            }
            append(')')
        }
        is WasmSectionFunctionTypeRecord -> buildString {
            append("(func")
            if (params.isNotEmpty()) {
                append(" (param ")
                append(params.joinToString(" ") { it.toTypeString() })
                append(')')
            }
            if (retTypes.isNotEmpty()) {
                append(" (result ")
                append(retTypes.joinToString(" ") { it.toTypeString() })
                append(')')
            }
            append(')')
        }
    }
}

private fun WasmFieldType.toFieldString(depth: Int): String {
    val type = storageType.toTypeString(depth)
    return if (isMutable) {
        "(field (mut $type))"
    } else {
        "(field $type)"
    }
}

private fun WasmFieldType.toFieldDeclarationString(depth: Int): String {
    val type = storageType.toTypeString(depth)
    return if (isMutable) {
        "(mut $type)"
    } else {
        type
    }
}

private fun WasmValueType<*>.toTypeString(@Suppress("UNUSED_PARAMETER") depth: Int = 0): String {
    return when (this) {
        is WasmF32Type -> "i32"
        is WasmF64Type -> "i64"
        is WasmI32Type -> "i32"
        is WasmI64Type -> "i64"
        is WasmVecType -> "v128"
        is WasmPactTypeI8 -> "i8"
        is WasmPactTypeI16 -> "i16"
        is WasmRefType -> {
            when (code) {
                is WasmNullableReferenceType,
                is WasmNonNullableReferenceType ->
                    "ref ${if (code is WasmNullableReferenceType) "null " else ""}${code.heapType.toHeapTypeResult()}"

                is WasmReferenceHeapType -> "ref ${code.heapType.toHeapTypeResult()}"
            }
        }
    }
}

private fun WasmHeapType.toHeapTypeResult(): String = when (this) {
    is WasmExnRefHeapType -> "exn"
    is WasmArrayRefHeapType -> "array"
    is WasmFuncHeapType -> "funcref"
    is WasmStructRefHeapType -> "struct"
    is WasmI31RefHeapType -> "i31ref"
    is WasmEqRefHeapType -> "eq"
    is WasmAnyRefHeapType -> "any"
    is WasmExternRefHeapType -> "extern"
    is WasmNoneHeapType -> "none"
    is WasmNoExnHeapType -> "noexn"
    is WasmIndexHeapType -> "index"
    is WasmNoFuncHeapType -> "nofunc"
    is WasmNoExternRefHeapType -> "noextern"
}
