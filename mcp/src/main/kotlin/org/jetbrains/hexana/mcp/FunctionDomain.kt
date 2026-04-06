package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.*

/**
 * Shared function-domain records and extraction helpers used by multiple MCP queries.
 *
 * This file intentionally stays below the query layer:
 * it owns function index-space resolution, import/function/export stitching,
 * instruction extraction, and local decoding, but it does not define MCP result DTOs.
 */
data class WasmExportFunctionRecord(val name: String, val entry: FunctionEntry)

sealed class FunctionEntry(open val funcidx: Int)

data class ImportFunctionEntry(
    override val funcidx: Int,
    val module: String,
    val name: String,
    val signature: ParameterSignature?
) : FunctionEntry(funcidx)

data class GeneralFunctionEntry(
    override val funcidx: Int,
    val signature: ParameterSignature?,
    val import: Import?,
    val exportedAs: List<String>,
    val definedIn: String,
    val codeOffset: Long?,
    val codeSize: Long?,
    val instructionCount: Long?,
) : FunctionEntry(funcidx)

data class Import(val module: String, val name: String)

internal fun CommonByteBuffer.listExportedInternal(wasmModule: WasmModule?): List<WasmExportFunctionRecord> {
    wasmModule ?: return emptyList()
    val records = with(wasmModule.exportSection) {
        this ?: return@with emptyList()
        scan()
    }
    val imports = importFunctions(wasmModule)
    val functions = listFunctionsInternal(wasmModule)
    return records
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .mapNotNull {
            val index = it.index.toInt()
            if (index < imports.size) {
                imports.getOrNull(index)?.let { function -> WasmExportFunctionRecord(it.name, function) }
            } else {
                functions.getOrNull(index)?.let { function -> WasmExportFunctionRecord(it.name, function) }
            }
        }
}

fun CommonByteBuffer.listFunctionsInternal(wasmModule: WasmModule?): List<GeneralFunctionEntry> {
    wasmModule ?: return emptyList()
    val types = types(wasmModule)
    val exports = listExports(wasmModule)
    val imports = importFunctions(wasmModule).mapIndexed { index, entry ->
        GeneralFunctionEntry(
            funcidx = index,
            import = Import(module = entry.module, name = entry.name),
            codeOffset = null,
            signature = entry.signature,
            exportedAs = exportedAs(exports, index),
            definedIn = "import",
            codeSize = null,
            instructionCount = null
        )
    }
    val records = with(wasmModule.functionSection) {
        this ?: return@with emptyList()
        this@listFunctionsInternal.parseFunctions()
    }
    val codes = with(wasmModule.codeSection) {
        this ?: return@with emptyList()
        this@listFunctionsInternal.scan()
    }
    val indexBase = imports.size
    val functions = records.mapIndexedNotNull { index, typeIndex ->
        resolveFunctionTypeRecord(types, typeIndex)?.toSignature()?.let { signature ->
            val funcidx = index + indexBase
            GeneralFunctionEntry(
                funcidx = funcidx,
                import = null,
                codeOffset = codes.getOrNull(index)?.offset?.toLong(),
                signature = signature,
                exportedAs = exportedAs(exports, funcidx),
                definedIn = "function_section",
                codeSize = codes.getOrNull(index)?.size?.toLong(),
                instructionCount = codes.getOrNull(index)?.opCodes?.size?.toLong()
            )
        }
    }
    return imports + functions
}

internal fun CommonByteBuffer.listFunctions(
    wasmModule: WasmModule?,
    offset: Int,
    limit: Int
): List<GeneralFunctionEntry> {
    val functions = listFunctionsInternal(wasmModule)
    return functions.slice(offset until minOf(functions.size, offset + limit))
}

internal fun CommonByteBuffer.functionsByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<FunctionEntry> {
    wasmModule ?: return emptyList()
    val functions = listFunctionsInternal(wasmModule)
    return functions.filter { indices.contains(it.funcidx) }
}

fun CommonByteBuffer.codeForFunctions(indices: List<Int>, wasmModule: WasmModule?): List<CodesByFunction> {
    wasmModule ?: return emptyList()
    val importFunctions = importFunctions(wasmModule)
    val importBase = importFunctions.size
    val instructionsByFunction = indices
        .filter { it < importBase }
        .associateWith { emptyList<String>() }
        .toMutableMap()

    val declaredFunctionIndices = indices.filter { it >= importBase }.map { it - importBase }
    wasmModule.codeSection?.let { codeSection ->
        with(codeSection) {
            this@codeForFunctions.scanOpcodesForIndices(declaredFunctionIndices).forEach { (declaredIndex, opCodes) ->
                instructionsByFunction[declaredIndex + importBase] = opCodes.map { it.watString().toString() }
            }
        }
    }

    return indices.distinct().mapNotNull { funcidx ->
        instructionsByFunction[funcidx]?.let { CodesByFunction(funcidx, it) }
    }
}

data class CodesByFunction(val funcidx: Int, val instructions: List<String>)

fun CommonByteBuffer.localsForFunctions(indices: List<Int>, wasmModule: WasmModule?): List<LocalsByFunction> {
    wasmModule ?: return emptyList()
    val importFunctions = importFunctions(wasmModule)
    val importBase = importFunctions.size
    val localsByFunction = indices
        .filter { it < importBase }
        .associateWith { emptyList<Local>() }
        .toMutableMap()

    val declaredFunctionIndices = indices.filter { it >= importBase }.map { it - importBase }
    wasmModule.codeSection?.let { codeSection ->
        with(codeSection) {
            scanLocalsEntries(declaredFunctionIndices).forEach { (declaredIndex, locals) ->
                localsByFunction[declaredIndex + importBase] = locals.serializeLocals()
            }
        }
    }

    return indices.distinct().mapNotNull { funcidx ->
        localsByFunction[funcidx]?.let { LocalsByFunction(funcidx, it) }
    }
}

data class LocalsByFunction(val funcidx: Int, val locals: List<Local>)

fun CommonByteBuffer.importFunctions(
    wasmModule: WasmModule?
): List<ImportFunctionEntry> {
    val records = with(wasmModule!!.importSection) {
        this ?: return@with emptyList()
        this@importFunctions.getImportRecords()
    }.filter { it.type is WasmTypeIndexExternalType }

    val types = types(wasmModule)
    return records.mapIndexed { index, record ->
        val signature = when (record.type) {
            is WasmTypeIndexExternalType -> (record.type as WasmTypeIndexExternalType).toSignature(types)
            else -> null
        }
        ImportFunctionEntry(index, record.prefix, record.name, signature)
    }
}

sealed class Local(open val count: Int)
data class LocalNum(override val count: Int, val type: Byte) : Local(count)
data class LocalPackI8(override val count: Int) : Local(count)
data class LocalPackI16(override val count: Int) : Local(count)
data class LocalVec(override val count: Int) : Local(count)
data class LocalEq(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalAny(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNone(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNoExn(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNoFunc(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNoExtern(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalIndex(override val count: Int, val type: Long, val nullable: Boolean) : Local(count)
data class LocalFunc(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalStruct(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalI31(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalExn(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalExtern(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalArray(override val count: Int, val nullable: Boolean) : Local(count)

fun Local.renderedType(): String =
    when (this) {
        is LocalNum -> "num:${byteToNumType(type)}"
        is LocalPackI8 -> "pack:i8"
        is LocalPackI16 -> "pack:i16"
        is LocalVec -> "vec:v128"
        is LocalEq -> "ref:eq${nullableSuffix()}"
        is LocalAny -> "ref:anyref${nullableSuffix()}"
        is LocalNone -> "ref:none${nullableSuffix()}"
        is LocalNoExn -> "ref:noexn${nullableSuffix()}"
        is LocalNoFunc -> "ref:nofunc${nullableSuffix()}"
        is LocalNoExtern -> "ref:noextern${nullableSuffix()}"
        is LocalFunc -> "ref:func${nullableSuffix()}"
        is LocalStruct -> "ref:struct${nullableSuffix()}"
        is LocalI31 -> "ref:i31${nullableSuffix()}"
        is LocalExn -> "ref:exn${nullableSuffix()}"
        is LocalExtern -> "ref:extern${nullableSuffix()}"
        is LocalArray -> "ref:array${nullableSuffix()}"
        is LocalIndex -> "ref:index:${type}${nullableSuffix()}"
    }

private fun List<WasmCodeLocalRecord>?.serializeLocals(): List<Local> = this?.map {
    when (it.type) {
        is WasmNumType -> LocalNum(it.local, it.type.code as Byte)
        is WasmVecType -> LocalVec(it.local)
        is WasmPactTypeI8 -> LocalPackI8(it.local)
        is WasmPactTypeI16 -> LocalPackI16(it.local)
        is WasmRefType -> {
            val referenceType = it.type.code as WasmReferenceType
            val heapType = referenceType.heapType
            when (heapType) {
                is WasmExnRefHeapType -> LocalExn(it.local, it.type.code is WasmNullableReferenceType)
                is WasmArrayRefHeapType -> LocalArray(it.local, it.type.code is WasmNullableReferenceType)
                is WasmFuncHeapType -> LocalFunc(it.local, it.type.code is WasmNullableReferenceType)
                is WasmStructRefHeapType -> LocalStruct(it.local, it.type.code is WasmNullableReferenceType)
                is WasmI31RefHeapType -> LocalI31(it.local, it.type.code is WasmNullableReferenceType)
                is WasmEqRefHeapType -> LocalEq(it.local, it.type.code is WasmNullableReferenceType)
                is WasmAnyRefHeapType -> LocalAny(it.local, it.type.code is WasmNullableReferenceType)
                is WasmExternRefHeapType -> LocalExtern(it.local, it.type.code is WasmNullableReferenceType)
                is WasmNoneHeapType -> LocalNone(it.local, it.type.code is WasmNullableReferenceType)
                is WasmNoExnHeapType -> LocalNoExn(it.local, it.type.code is WasmNullableReferenceType)
                is WasmNoFuncHeapType -> LocalNoFunc(it.local, it.type.code is WasmNullableReferenceType)
                is WasmNoExternRefHeapType -> LocalNoExtern(it.local, it.type.code is WasmNullableReferenceType)
                is WasmIndexHeapType -> LocalIndex(it.local, heapType.type, it.type.code is WasmNullableReferenceType)
            }
        }
    }
} ?: emptyList()

private fun exportedAs(exports: List<ExportRecord>, index: Int): List<String> {
    return exports.filter { record -> record.kind.toInt() == 0 && record.externIndex.toInt() == index }
        .map { record -> record.name }
}

private fun Local.nullableSuffix(): String =
    // Note: Do not insert there just else. This will be refactored soon, we shouldn't have branches with types and unresolved text.
    when (this) {
        is LocalNum -> ""
        is LocalPackI8 -> ""
        is LocalPackI16 -> ""
        is LocalVec -> ""
        is LocalEq -> if (nullable) "?" else ""
        is LocalAny -> if (nullable) "?" else ""
        is LocalNone -> if (nullable) "?" else ""
        is LocalNoExn -> if (nullable) "?" else ""
        is LocalNoFunc -> if (nullable) "?" else ""
        is LocalNoExtern -> if (nullable) "?" else ""
        is LocalFunc -> if (nullable) "?" else ""
        is LocalStruct -> if (nullable) "?" else ""
        is LocalI31 -> if (nullable) "?" else ""
        is LocalExn -> if (nullable) "?" else ""
        is LocalExtern -> if (nullable) "?" else ""
        is LocalArray -> if (nullable) "?" else ""
        is LocalIndex -> if (nullable) "?" else ""
    }

private fun byteToNumType(type: Byte): String =
    type.toWasmNumType().toSignature()
