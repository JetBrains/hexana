package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.Local
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.model.functions.FunctionCallGraphLocalResult
import org.jetbrains.hexana.mcp.model.functions.FunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.FunctionCallTargetResult
import org.jetbrains.hexana.mcp.model.functions.FunctionDirectCallResult
import org.jetbrains.hexana.mcp.model.functions.FunctionImportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionIndirectCallResult
import org.jetbrains.hexana.mcp.model.functions.FunctionMemoryWriteResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.GetFunctionCallGraphResult
import org.jetbrains.hexana.mcp.renderedMcpType
import org.jetbrains.hexana.mcp.serializeLocals
import org.jetbrains.hexana.mcp.watString
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.atomic.Atomic
import org.jetbrains.hexana.wasm.opcodes.vector.V128

fun WasmBinaryProvider.readFunctionCallGraph(indices: List<Int>): GetFunctionCallGraphResult {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }

    return GetFunctionCallGraphResult(
        functions = indices.distinct().mapNotNull { funcidx ->
            functionsByIndex[funcidx]?.toFunctionCallGraphResult(
                provider = this,
                functionsByIndex = functionsByIndex
            )
        }
    )
}

private fun GeneralFunctionEntry.toFunctionCallGraphResult(
    provider: WasmBinaryProvider,
    functionsByIndex: Map<Int, GeneralFunctionEntry>
): FunctionCallGraphResult {
    val codeRecord = if (import == null) provider.getCodeRecordByIndex(funcidx - provider.importFuncCount) else null
    val localGroups = codeRecord?.locals.serializeLocals()
    val scan = codeRecord?.opCodes?.scanFunctionBody(functionsByIndex) ?: FunctionBodyScan.empty()

    return FunctionCallGraphResult(
        funcidx = funcidx,
        kind = functionKind(),
        typeidx = typeidx,
        signature = signature?.toFunctionSignatureResult(),
        importInfo = import?.let { FunctionImportResult(module = it.module, name = it.name) },
        exportedAs = exportedAs,
        codeOffset = codeOffset,
        codeSize = codeSize,
        instructionCount = instructionCount,
        locals = localGroups.map { it.toFunctionCallGraphLocalResult() },
        directCalls = scan.directCalls,
        directImportedCalls = scan.directCalls.filter { it.target?.kind == "import" },
        indirectCalls = scan.indirectCalls,
        directMemoryWrites = scan.directMemoryWrites
    )
}

private data class FunctionBodyScan(
    val directCalls: List<FunctionDirectCallResult>,
    val indirectCalls: List<FunctionIndirectCallResult>,
    val directMemoryWrites: List<FunctionMemoryWriteResult>
) {
    companion object {
        fun empty(): FunctionBodyScan = FunctionBodyScan(
            directCalls = emptyList(),
            indirectCalls = emptyList(),
            directMemoryWrites = emptyList()
        )
    }
}

private fun List<OpCode>.scanFunctionBody(functionsByIndex: Map<Int, GeneralFunctionEntry>): FunctionBodyScan {
    val directCalls = mutableListOf<FunctionDirectCallResult>()
    val indirectCalls = mutableListOf<FunctionIndirectCallResult>()
    val directMemoryWrites = mutableListOf<FunctionMemoryWriteResult>()

    fun walk(ops: List<OpCode>) {
        for (op in ops) {
            when (op) {
                is OpCodes.Flow.Call -> directCalls += op.toDirectCallResult(op.value.toInt(), functionsByIndex)
                is OpCodes.Flow.ReturnCall -> directCalls += op.toDirectCallResult(op.value.toInt(), functionsByIndex)
                is OpCodes.Flow.CallIndirect -> indirectCalls += op.toIndirectCallResult(op.value0, op.value1)
                is OpCodes.Flow.ReturnCallIndirect -> indirectCalls += op.toIndirectCallResult(
                    typeidx = op.value0.toInt(),
                    tableidx = op.value1.toInt()
                )

                is OpCodes.Flow.Block -> walk(op.value)
                is OpCodes.Flow.TryTable -> walk(op.code)
                else -> op.toMemoryWriteResult()?.let { directMemoryWrites += it }
            }
        }
    }

    walk(this)

    return FunctionBodyScan(
        directCalls = directCalls,
        indirectCalls = indirectCalls,
        directMemoryWrites = directMemoryWrites
    )
}

private fun OpCode.toDirectCallResult(
    targetFuncidx: Int,
    functionsByIndex: Map<Int, GeneralFunctionEntry>
): FunctionDirectCallResult {
    val text = watString().toString()
    return FunctionDirectCallResult(
        op = text.instructionOp(),
        text = text,
        offset = offsetOrNull(),
        targetFuncidx = targetFuncidx,
        target = functionsByIndex[targetFuncidx]?.toFunctionCallTargetResult()
    )
}

private fun OpCode.toIndirectCallResult(typeidx: Int, tableidx: Int): FunctionIndirectCallResult {
    val text = watString().toString()
    return FunctionIndirectCallResult(
        op = text.instructionOp(),
        text = text,
        offset = offsetOrNull(),
        typeidx = typeidx,
        tableidx = tableidx
    )
}

private fun OpCode.toMemoryWriteResult(): FunctionMemoryWriteResult? {
    val isMemoryWrite = when (this) {
        is OpCodes.Memory.i32.Store,
        is OpCodes.Memory.i32.Store8,
        is OpCodes.Memory.i32.Store16,
        is OpCodes.Memory.i64.Store,
        is OpCodes.Memory.i64.Store8,
        is OpCodes.Memory.i64.Store16,
        is OpCodes.Memory.i64.Store32,
        is OpCodes.Memory.f32.Store,
        is OpCodes.Memory.f64.Store,
        is OpCodes.Memory.Init,
        is OpCodes.Memory.Copy,
        is OpCodes.Memory.Fill,
        is Atomic.I32Store,
        is Atomic.I32Store8,
        is Atomic.I32Store16,
        is Atomic.I64Store,
        is Atomic.I64Store8,
        is Atomic.I64Store16,
        is Atomic.I64Store32,
        is V128.Store,
        is V128.Store8Lane,
        is V128.Store16Lane,
        is V128.Store32Lane,
        is V128.Store64Lane -> true

        else -> false
    }
    if (!isMemoryWrite) return null

    val text = watString().toString()
    val memArg = memoryArgumentOrNull()
    return FunctionMemoryWriteResult(
        op = text.instructionOp(),
        text = text,
        offset = offsetOrNull(),
        memoryOffset = memArg?.offset,
        alignment = memArg?.align
    )
}

private fun OpCode.memoryArgumentOrNull(): MemArg? = when (this) {
    is OpCodes.Memory.i32.Store -> value
    is OpCodes.Memory.i32.Store8 -> value
    is OpCodes.Memory.i32.Store16 -> value
    is OpCodes.Memory.i64.Store -> value
    is OpCodes.Memory.i64.Store8 -> value
    is OpCodes.Memory.i64.Store16 -> value
    is OpCodes.Memory.i64.Store32 -> value
    is OpCodes.Memory.f32.Store -> value
    is OpCodes.Memory.f64.Store -> value
    is Atomic.I32Store -> mem
    is Atomic.I32Store8 -> mem
    is Atomic.I32Store16 -> mem
    is Atomic.I64Store -> mem
    is Atomic.I64Store8 -> mem
    is Atomic.I64Store16 -> mem
    is Atomic.I64Store32 -> mem
    is V128.Store -> mem
    is V128.Store8Lane -> mem
    is V128.Store16Lane -> mem
    is V128.Store32Lane -> mem
    is V128.Store64Lane -> mem
    else -> null
}

private fun GeneralFunctionEntry.toFunctionCallTargetResult(): FunctionCallTargetResult {
    return FunctionCallTargetResult(
        funcidx = funcidx,
        kind = functionKind(),
        typeidx = typeidx,
        signature = signature?.toFunctionSignatureResult(),
        importInfo = import?.let { FunctionImportResult(module = it.module, name = it.name) },
        exportedAs = exportedAs
    )
}

private fun GeneralFunctionEntry.functionKind(): String {
    return if (import != null) "import" else "local"
}

private fun ParameterSignature.toFunctionSignatureResult(): FunctionSignatureResult {
    return FunctionSignatureResult(
        params = params,
        results = ret
    )
}

private fun Local.toFunctionCallGraphLocalResult(): FunctionCallGraphLocalResult {
    return FunctionCallGraphLocalResult(
        count = count,
        type = renderedMcpType()
    )
}

private fun OpCode.offsetOrNull(): Long? = (this as? OpCodeWithOffset)?.offset?.toLong()

private fun String.instructionOp(): String {
    return lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        .orEmpty()
        .trimEnd('{')
}
