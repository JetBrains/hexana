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

private fun WasmTypeRecord.toTypeResult(index: Int? = null): TypeResult = when (this) {
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
        entries = entries.map { it.toTypeResult() }
    )

    is WasmSectionFunctionTypeRecord -> TypeResult(
        kind = "func",
        typeidx = index,
        params = params.map { it.toTypeResult() },
        retTypes = retTypes.map { it.toTypeResult() }
    )
}

private fun WasmFieldType.toTypeFieldResult(): TypeFieldResult {
    return TypeFieldResult(
        type = storageType.toTypeResult(),
        mutable = isMutable
    )
}

private fun WasmValueType<*>.toTypeResult(): TypeResult {
    return when (this) {
        is WasmF32Type -> TypeResult(kind = "val", type = "i32")
        is WasmF64Type -> TypeResult(kind = "val", type = "i64")
        is WasmI32Type -> TypeResult(kind = "val", type = "i32")
        is WasmI64Type -> TypeResult(kind = "val", type = "i64")
        is WasmVecType -> TypeResult(kind = "val", type = "v128")
        is WasmPactTypeI8 -> TypeResult(kind = "packed", packType = "i8")
        is WasmPactTypeI16 -> TypeResult(kind = "packed", packType = "i16")
        is WasmRefType -> {
            when (code) {
                is WasmNullableReferenceType,
                is WasmNonNullableReferenceType -> TypeResult(
                    kind = "ref",
                    refKind = code.heapType.toHeapTypeResult(),
                    nullable = code is WasmNullableReferenceType
                )

                is WasmReferenceHeapType -> TypeResult(
                    kind = "ref",
                    refKind = code.heapType.toHeapTypeResult()
                )
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
