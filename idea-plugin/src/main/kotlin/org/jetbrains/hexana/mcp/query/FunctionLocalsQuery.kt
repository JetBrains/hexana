package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.Local
import org.jetbrains.hexana.mcp.LocalAny
import org.jetbrains.hexana.mcp.LocalArray
import org.jetbrains.hexana.mcp.LocalEq
import org.jetbrains.hexana.mcp.LocalExn
import org.jetbrains.hexana.mcp.LocalExtern
import org.jetbrains.hexana.mcp.LocalFunc
import org.jetbrains.hexana.mcp.LocalI31
import org.jetbrains.hexana.mcp.LocalIndex
import org.jetbrains.hexana.mcp.LocalNoExn
import org.jetbrains.hexana.mcp.LocalNoExtern
import org.jetbrains.hexana.mcp.LocalNoFunc
import org.jetbrains.hexana.mcp.LocalNone
import org.jetbrains.hexana.mcp.LocalNum
import org.jetbrains.hexana.mcp.LocalPackI16
import org.jetbrains.hexana.mcp.LocalPackI8
import org.jetbrains.hexana.mcp.LocalStruct
import org.jetbrains.hexana.mcp.LocalVec
import org.jetbrains.hexana.mcp.LocalsByFunction
import org.jetbrains.hexana.mcp.localsForFunctions
import org.jetbrains.hexana.mcp.model.functions.FunctionLocalsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.LocalDeclarationResult
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.readLocalsForFunctions(
    wasmModule: WasmModule,
    indices: List<Int>
): GetLocalsForFunctionsResult {
    return GetLocalsForFunctionsResult(
        functions = localsForFunctions(indices, wasmModule).map { it.toFunctionLocalsResult() }
    )
}

private fun LocalsByFunction.toFunctionLocalsResult(): FunctionLocalsResult {
    return FunctionLocalsResult(
        funcidx = funcidx,
        locals = locals.map { it.toLocalDeclarationResult() }
    )
}

private fun Local.toLocalDeclarationResult(): LocalDeclarationResult {
    return LocalDeclarationResult(
        local = count,
        type = stringifyLocalType(this)
    )
}

private fun stringifyLocalType(value: Local): String =
    when (value) {
        is LocalNum -> "num:${byteToNumType(value.type)}"
        is LocalPackI8 -> "pack:i8"
        is LocalPackI16 -> "pack:i16"
        is LocalVec -> "vec:v128"
        is LocalEq -> "ref:eq${nullableSuffix(value)}"
        is LocalAny -> "ref:anyref${nullableSuffix(value)}"
        is LocalNone -> "ref:none${nullableSuffix(value)}"
        is LocalNoExn -> "ref:noexn${nullableSuffix(value)}"
        is LocalNoFunc -> "ref:nofunc${nullableSuffix(value)}"
        is LocalNoExtern -> "ref:noextern${nullableSuffix(value)}"
        is LocalFunc -> "ref:func${nullableSuffix(value)}"
        is LocalStruct -> "ref:struct${nullableSuffix(value)}"
        is LocalI31 -> "ref:i31${nullableSuffix(value)}"
        is LocalExn -> "ref:exn${nullableSuffix(value)}"
        is LocalExtern -> "ref:extern${nullableSuffix(value)}"
        is LocalArray -> "ref:array${nullableSuffix(value)}"
        is LocalIndex -> "ref:index:${value.type}${nullableSuffix(value)}"
    }

private fun nullableSuffix(value: Local): String =
    when (value) {
        is LocalEq, is LocalAny, is LocalNone, is LocalNoExn, is LocalNoFunc,
        is LocalNoExtern, is LocalFunc, is LocalStruct, is LocalI31,
        is LocalExn, is LocalExtern, is LocalArray, is LocalIndex ->
            if ((value as? Any)?.let { (it as? LocalEq)?.nullable } == true) "?" else ""
        else -> ""
    }

private fun byteToNumType(type: Byte): String =
    when (type.toInt()) {
        0x7F -> "i32"
        0x7E -> "i64"
        0x7D -> "f32"
        0x7C -> "f64"
        else -> "unknown"
    }
