package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.*

fun wasmValueTypeName(type: WasmValueType<*>): String = when (type) {
    is WasmI32Type -> "i32"
    is WasmI64Type -> "i64"
    is WasmF32Type -> "f32"
    is WasmF64Type -> "f64"
    is WasmVecType -> "v128"
    else -> type.toString()
}

fun exportKindLabel(export: WasmExportRecordEntry): String = when (export) {
    is WasmFunctionExportRecordEntry -> "function"
    is WasmGlobalExportRecordEntry -> "global"
    is WasmMemoryExportRecordEntry -> "memory"
    is WasmTableExportRecordEntry -> "table"
    is WasmTagExportRecordEntry -> "tag"
    is WasmUnsupportedExportRecordEntry -> "unknown"
}

fun exportIndex(export: WasmExportRecordEntry): String = when (export) {
    is WasmFunctionExportRecordEntry -> export.index.toString()
    is WasmGlobalExportRecordEntry -> export.index.toString()
    is WasmMemoryExportRecordEntry -> export.index.toString()
    is WasmTableExportRecordEntry -> export.index.toString()
    is WasmTagExportRecordEntry -> export.index.toString()
    is WasmUnsupportedExportRecordEntry -> export.index.toString()
}

fun importKindLabel(type: WasmExternalType): String = when (type) {
    is WasmTypeIndexExternalType -> "function"
    is WasmTableExternalType -> "table"
    is WasmMemType -> "memory"
    is WasmGlobalType -> "global"
    is WasmTagType -> "tag"
}

fun isInternalName(name: String): Boolean = name.startsWith("__")

fun isTrivialType(type: WasmValueType<*>): Boolean = type is WasmNumType

fun resolveFunctionSignature(
    wasmBinaryProvider: WasmBinaryProvider,
    export: WasmFunctionExportRecordEntry,
): WasmSectionFunctionTypeRecord? {
    val importedFunctionCount = wasmBinaryProvider.imports.count { it.type is WasmTypeIndexExternalType }
    val localIndex = export.index.toInt() - importedFunctionCount
    if (localIndex < 0) return null
    return wasmBinaryProvider.functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord
}

fun formatSignature(sig: WasmSectionFunctionTypeRecord): String {
    val params = sig.params.joinToString(", ") { wasmValueTypeName(it) }
    val result = if (sig.retTypes.isEmpty()) "void" else sig.retTypes.joinToString(", ") { wasmValueTypeName(it) }
    return "($params) -> $result"
}

fun resolveCoreSignature(
    export: WasmFunctionExportRecordEntry,
    functions: List<WasmTypeRecord>,
    importFuncCount: Int,
): String {
    val localIndex = export.index.toInt() - importFuncCount
    if (localIndex < 0) return ""
    val sig = functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord ?: return ""
    val params = sig.params.joinToString(", ") { wasmValueTypeName(it) }
    val result = if (sig.retTypes.isEmpty()) "void" else sig.retTypes.joinToString(", ") { wasmValueTypeName(it) }
    return "($params) → $result"
}

fun limitsStr(limits: WasmLimits<*>): String {
    val min = limits.min
    val max = limits.max
    return if (max != null) "$min $max" else "$min"
}
