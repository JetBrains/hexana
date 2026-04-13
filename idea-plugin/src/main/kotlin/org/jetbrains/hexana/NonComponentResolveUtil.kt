package org.jetbrains.hexana

import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.*

fun isInternalName(name: String): Boolean = name.startsWith("__")

fun resolveFunctionSignature(
    wasmBinaryProvider: WasmBinaryProvider,
    export: WasmFunctionExportRecordEntry,
): WasmSectionFunctionTypeRecord? {
    val importedFunctionCount = wasmBinaryProvider.imports.count { it.type is WasmTypeIndexExternalType }
    val localIndex = export.index.toInt() - importedFunctionCount
    if (localIndex < 0) return null
    return wasmBinaryProvider.functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord
}

fun isTrivialType(type: WasmValueType<*>): Boolean = type is WasmNumType

fun wasmTypeName(type: WasmValueType<*>): String = when (type) {
    is WasmI32Type -> "i32"
    is WasmI64Type -> "i64"
    is WasmF32Type -> "f32"
    is WasmF64Type -> "f64"
    else -> type.toString()
}

fun formatSignature(sig: WasmSectionFunctionTypeRecord): String {
    val params = sig.params.joinToString(", ") { wasmTypeName(it) }
    val result = sig.retTypes.firstOrNull()?.let { wasmTypeName(it) } ?: "void"
    return "($params) -> $result"
}
