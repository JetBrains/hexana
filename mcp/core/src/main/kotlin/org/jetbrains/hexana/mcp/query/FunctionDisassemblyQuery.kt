package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.Local
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.model.functions.DisassembledLocalGroupResult
import org.jetbrains.hexana.mcp.model.functions.DisassembleFunctionResult
import org.jetbrains.hexana.mcp.model.functions.DisassembleFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.DisassembledInstructionResult
import org.jetbrains.hexana.mcp.model.functions.FunctionImportResult
import org.jetbrains.hexana.mcp.renderedType
import org.jetbrains.hexana.mcp.serializeLocals
import org.jetbrains.hexana.mcp.watString
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord

fun WasmBinaryProvider.readFunctionDisassembly(funcidx: Int): DisassembleFunctionResult {
    val entry = listFunctionEntries().singleOrNull { it.funcidx == funcidx }
        ?: throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.OUT_OF_RANGE,
            "Function index not found"
        )

    val codeRecord = if (entry.import == null) getCodeRecordByIndex(funcidx - importFuncCount) else null
    val localGroups = codeRecord?.locals.serializeLocals()
    val locals = localGroups.map { it.renderedType() }
    val instructions = codeRecord?.opCodes.orEmpty().map { it.toDisassembledInstructionResult() }

    return DisassembleFunctionResult(
        funcidx = entry.funcidx,
        typeidx = entry.typeidx,
        signature = entry.signature?.toDisassembleFunctionSignatureResult(),
        locals = locals,
        localGroups = localGroups.map { it.toDisassembledLocalGroupResult() },
        isImported = entry.import != null,
        isExported = entry.exportedAs.isNotEmpty(),
        exportNames = entry.exportedAs,
        importInfo = entry.import?.let { FunctionImportResult(module = it.module, name = it.name) },
        codeOffset = codeRecord?.offset?.toLong(),
        codeSize = codeRecord?.size?.toLong(),
        instructions = instructions,
        wat = entry.renderWat(localGroups, instructions),
        hex = codeRecord?.renderHex(this)
    )
}

private fun ParameterSignature.toDisassembleFunctionSignatureResult(): DisassembleFunctionSignatureResult {
    return DisassembleFunctionSignatureResult(
        params = params,
        results = ret
    )
}

private fun Local.toDisassembledLocalGroupResult(): DisassembledLocalGroupResult {
    return DisassembledLocalGroupResult(
        count = count,
        type = renderedType()
    )
}

private fun OpCode.toDisassembledInstructionResult(): DisassembledInstructionResult {
    val text = watString().toString()
    val parts = text.lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    val op = parts.firstOrNull()?.trimEnd('{') ?: text

    return DisassembledInstructionResult(
        op = op,
        args = parts.drop(1).map { it.trimEnd('{') },
        text = text,
        offset = (this as? OpCodeWithOffset)?.offset?.toLong()
    )
}

private fun GeneralFunctionEntry.renderWat(
    localGroups: List<Local>,
    instructions: List<DisassembledInstructionResult>
): String? {
    if (import != null) return null

    return buildString {
        append("(func")
        signature?.params.orEmpty().forEach { param ->
            append(" (param ")
            append(param)
            append(")")
        }
        signature?.ret.orEmpty().forEach { result ->
            append(" (result ")
            append(result)
            append(")")
        }
        localGroups.forEach { local ->
            append("\n  (local ")
            append(List(local.count) { local.renderedType().toWatLocalType() }.joinToString(" "))
            append(")")
        }
        instructions.forEach { instruction ->
            append("\n")
            append(instruction.text.prependIndent("  "))
        }
        append("\n)")
    }
}

private fun String.toWatLocalType(): String {
    return when {
        startsWith("num:") -> removePrefix("num:")
        startsWith("vec:") -> removePrefix("vec:")
        else -> this
    }
}

private fun WasmCodeRecord.renderHex(provider: WasmBinaryProvider): String {
    val buffer = provider.bytes
    return (offset until offset + size).joinToString(" ") { index ->
        buffer[index].hexByte()
    }
}

private fun Byte.hexByte(): String {
    val value = toInt() and 0xFF
    return "${HEX_CHARS[value ushr 4]}${HEX_CHARS[value and 0x0F]}"
}

private const val HEX_CHARS = "0123456789abcdef"
