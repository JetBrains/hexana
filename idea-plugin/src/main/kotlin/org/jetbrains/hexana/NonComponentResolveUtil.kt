package org.jetbrains.hexana

import org.jetbrains.hexana.shared.formatSignature as sharedFormatSignature
import org.jetbrains.hexana.shared.isInternalName as sharedIsInternalName
import org.jetbrains.hexana.shared.isTrivialType as sharedIsTrivialType
import org.jetbrains.hexana.shared.resolveFunctionSignature as sharedResolveFunctionSignature
import org.jetbrains.hexana.shared.wasmValueTypeName as sharedWasmTypeName
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.*

fun isInternalName(name: String): Boolean = sharedIsInternalName(name)

fun resolveFunctionSignature(
    wasmBinaryProvider: WasmBinaryProvider,
    export: WasmFunctionExportRecordEntry,
): WasmSectionFunctionTypeRecord? = sharedResolveFunctionSignature(wasmBinaryProvider, export)

fun isTrivialType(type: WasmValueType<*>): Boolean = sharedIsTrivialType(type)

fun wasmTypeName(type: WasmValueType<*>): String = sharedWasmTypeName(type)

fun formatSignature(sig: WasmSectionFunctionTypeRecord): String = sharedFormatSignature(sig)
