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
import org.jetbrains.hexana.mcp.renderedMcpType
import org.jetbrains.hexana.mcp.renderedType
import org.jetbrains.hexana.mcp.serializeLocals
import org.jetbrains.hexana.mcp.watString
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord

fun WasmBinaryProvider.readFunctionDisassembly(funcidx: Int): DisassembleFunctionResult =
    readFunctionDisassemblyInternal(funcidx, maxOutputBytes = null)

fun WasmBinaryProvider.readFunctionDisassembly(funcidx: Int, maxOutputBytes: Int): DisassembleFunctionResult =
    readFunctionDisassemblyInternal(funcidx, maxOutputBytes)

private fun WasmBinaryProvider.readFunctionDisassemblyInternal(
    funcidx: Int,
    maxOutputBytes: Int?
): DisassembleFunctionResult {
    val entry = listFunctionEntries().singleOrNull { it.funcidx == funcidx }
        ?: throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.OUT_OF_RANGE,
            "Function index not found"
        )

    val codeRecord = if (entry.import == null) getCodeRecordByIndex(funcidx - importFuncCount) else null
    val localGroups = codeRecord?.locals.serializeLocals()
    maxOutputBytes?.let { requireSafeLocalExpansion(funcidx, localGroups, it) }
    val locals = localGroups.map { it.renderedMcpType() }
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
        wat = entry.renderWat(localGroups, instructions, maxOutputBytes?.disassemblyPreviewBytes()),
        hex = codeRecord?.renderHex(this, maxOutputBytes?.disassemblyPreviewBytes())
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
        type = renderedMcpType()
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
    instructions: List<DisassembledInstructionResult>,
    maxPreviewBytes: Int?
): String? {
    if (import != null) return null
    if (maxPreviewBytes == null) {
        return buildString {
            append("(func")
            signature?.params.orEmpty().forEach { append(" (param $it)") }
            signature?.ret.orEmpty().forEach { append(" (result $it)") }
            localGroups.forEach { local ->
                append("\n  (local")
                val type = " ${local.renderedType().toWatLocalType()}"
                repeat(local.count) { append(type) }
                append(")")
            }
            instructions.forEach { append("\n${it.text.prependIndent("  ")}") }
            append("\n)")
        }
    }

    val output = BoundedTextBuilder(maxPreviewBytes, WAT_TRUNCATION_MARKER, "WAT preview")
    if (!output.append("(func")) return output.truncatedText()
    signature?.params.orEmpty().forEach { param ->
        if (!output.append(" (param $param)")) return output.truncatedText()
    }
    signature?.ret.orEmpty().forEach { result ->
        if (!output.append(" (result $result)")) return output.truncatedText()
    }
    localGroups.forEach { local ->
        if (!output.append("\n  (local")) return output.truncatedText()
        val type = " ${local.renderedType().toWatLocalType()}"
        repeat(local.count) {
            if (!output.append(type)) return output.truncatedText()
        }
        if (!output.append(")")) return output.truncatedText()
    }
    instructions.forEach { instruction ->
        if (!output.append("\n${instruction.text.prependIndent("  ")}")) return output.truncatedText()
    }
    if (!output.append("\n)")) return output.truncatedText()
    return output.completeText()
}

private fun requireSafeLocalExpansion(funcidx: Int, localGroups: List<Local>, maxExpandedLocals: Int) {
    var total = 0L
    localGroups.forEach { local ->
        total += local.count.toLong()
        if (local.count < 0 || total > maxExpandedLocals.toLong()) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "Function $funcidx declares more than $maxExpandedLocals locals; " +
                    "refusing to expand them under the configured output limit."
            )
        }
    }
}

internal class BoundedTextBuilder(
    private val maxBytes: Int,
    private val truncationMarker: String,
    private val description: String
) {
    private val pieces = mutableListOf<EncodedPiece>()
    private val markerBytes = truncationMarker.encodeToByteArray().size
    private var contentBytes = 0

    fun append(value: String): Boolean {
        val encodedSize = value.encodeToByteArray().size
        if (encodedSize > maxBytes - contentBytes) return false
        pieces += EncodedPiece(value, encodedSize)
        contentBytes += encodedSize
        return true
    }

    fun completeText(): String = pieces.joinToString(separator = "") { it.value }

    fun truncatedText(): String {
        if (maxBytes < markerBytes) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "Configured $description budget of $maxBytes bytes cannot fit the explicit " +
                    "truncation marker, which requires $markerBytes bytes."
            )
        }
        while (contentBytes + markerBytes > maxBytes) {
            val removed = pieces.removeLast()
            contentBytes -= removed.encodedBytes
        }
        return pieces.joinToString(separator = "", postfix = truncationMarker) { it.value }
    }

    private data class EncodedPiece(val value: String, val encodedBytes: Int)
}

private fun String.toWatLocalType(): String {
    return when {
        startsWith("num:") -> removePrefix("num:")
        startsWith("vec:") -> removePrefix("vec:")
        else -> this
    }
}

private fun WasmCodeRecord.renderHex(provider: WasmBinaryProvider, maxPreviewBytes: Int?): String {
    val buffer = provider.bytes
    if (maxPreviewBytes == null) {
        return (offset until offset + size).joinToString(" ") { index -> buffer[index].hexByte() }
    }
    val output = BoundedTextBuilder(maxPreviewBytes, HEX_TRUNCATION_MARKER, "hex preview")
    for (index in offset until offset + size) {
        val separator = if (index == offset) "" else " "
        if (!output.append(separator + buffer[index].hexByte())) {
            return output.truncatedText()
        }
    }
    return output.completeText()
}

private fun Byte.hexByte(): String {
    val value = toInt() and 0xFF
    return "${HEX_CHARS[value ushr 4]}${HEX_CHARS[value and 0x0F]}"
}

private const val HEX_CHARS = "0123456789abcdef"
private const val DISASSEMBLY_PREVIEW_BUDGET_DIVISOR = 32
private const val WAT_TRUNCATION_MARKER = "\n  ;; [truncated: remaining WAT omitted by output limit]\n)"
private const val HEX_TRUNCATION_MARKER = " [truncated: remaining code bytes omitted by output limit]"

private fun Int.disassemblyPreviewBytes(): Int = (this / DISASSEMBLY_PREVIEW_BUDGET_DIVISOR).coerceAtLeast(1)
