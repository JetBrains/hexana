package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.binaries.BinaryFunctionResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryFunctionsResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryOmittedResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryWarningResult
import org.jetbrains.hexana.mcp.toModuleIdentity
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeFunctionRecord
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

fun org.jetbrains.hexana.providers.BinaryProvider.readBinaryFunctions(
    offset: Int = 0,
    limit: Int = HexanaAnalysisLimits.DEFAULT_QUERY_LIMIT,
    maxLimit: Int = HexanaAnalysisLimits.MAX_CANDIDATE_COUNT,
    pathLabel: String? = null,
): BinaryFunctionsResult =
    readBinaryFunctionsPage(query = emptyList(), offset = offset, limit = limit, maxLimit = maxLimit, pathLabel = pathLabel)

fun org.jetbrains.hexana.providers.BinaryProvider.searchBinaryFunctions(
    query: List<String>,
    offset: Int = 0,
    limit: Int = HexanaAnalysisLimits.DEFAULT_QUERY_LIMIT,
    maxLimit: Int = HexanaAnalysisLimits.MAX_CANDIDATE_COUNT,
    pathLabel: String? = null,
): BinaryFunctionsResult =
    readBinaryFunctionsPage(query = query, offset = offset, limit = limit, maxLimit = maxLimit, pathLabel = pathLabel)

private fun org.jetbrains.hexana.providers.BinaryProvider.readBinaryFunctionsPage(
    query: List<String>,
    offset: Int,
    limit: Int,
    maxLimit: Int,
    pathLabel: String?,
): BinaryFunctionsResult {
    if (offset < 0) {
        throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "`offset` must be non-negative")
    }

    val warnings = mutableListOf<BinaryWarningResult>()
    val configuredMaxLimit = minOf(HexanaAnalysisLimits.MAX_QUERY_LIMIT, maxLimit.coerceAtLeast(1))
    val boundedLimit = limit.coerceIn(1, configuredMaxLimit)
    if (boundedLimit != limit) {
        warnings += BinaryWarningResult("clamped_limit", "`limit` was clamped to $boundedLimit.")
    }

    val functions = binaryFunctionRecords(warnings)
    val terms = query.map { it.trim() }.filter { it.isNotEmpty() }
    val filteredFunctions = functions.filterByTerms(terms)
    val page = filteredFunctions.drop(offset).take(boundedLimit)
    val omitted = buildList {
        if (offset > 0) {
            add(BinaryOmittedResult("functions", minOf(offset, filteredFunctions.size), "before requested `offset`"))
        }
        val afterPage = filteredFunctions.size - offset - page.size
        if (afterPage > 0) {
            add(BinaryOmittedResult("functions", afterPage, "outside `limit` bounds"))
        }
    }

    return BinaryFunctionsResult(
        module = toModuleIdentity(pathLabel),
        query = terms,
        total = filteredFunctions.size,
        offset = offset,
        limit = boundedLimit,
        returned = page.size,
        nextOffset = (offset + page.size).takeIf { it < filteredFunctions.size },
        functions = page,
        omitted = omitted,
        warnings = warnings,
    )
}

private fun org.jetbrains.hexana.providers.BinaryProvider.binaryFunctionRecords(
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryFunctionResult> =
    when (this) {
        is WasmBinaryProvider -> wasmBinaryFunctionRecords()
        is NativeBinaryProvider -> nativeBinaryFunctionRecords(warnings)
        else -> {
            warnings += BinaryWarningResult("no_records", "No function records are available for this binary format.")
            emptyList()
        }
    }

private fun WasmBinaryProvider.wasmBinaryFunctionRecords(): List<BinaryFunctionResult> {
    val importedFunctions = imports.filter { it.type is WasmTypeIndexExternalType }
    val importRecords = importedFunctions.mapIndexed { funcidx, record ->
        val typeidx = (record.type as WasmTypeIndexExternalType).index
        BinaryFunctionResult(
            funcidx = funcidx,
            source = "import",
            name = "${record.prefix}.${record.name}",
            imported = true,
            importModule = record.prefix,
            importName = record.name,
            typeidx = typeidx.toInt(),
            fileOffset = record.offset.toLong(),
            size = record.size.toLong(),
        )
    }
    val typeIndices = wasmModule?.functionSection?.let { section ->
        with(section) { bytes.parseFunctions() }
    }.orEmpty()
    val localRecords = typeIndices.mapIndexed { codeIndex, typeidx ->
        val funcidx = importedFunctions.size + codeIndex
        val codeOffset = codeOffsets.getOrNull(codeIndex)
        BinaryFunctionResult(
            funcidx = funcidx,
            source = "code",
            name = nameSection?.functionSubsection?.namemap?.get(funcidx),
            imported = false,
            typeidx = typeidx,
            fileOffset = codeOffset?.offset?.toLong(),
            size = codeOffset?.size?.toLong(),
        )
    }
    return importRecords + localRecords
}

private fun NativeBinaryProvider.nativeBinaryFunctionRecords(
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryFunctionResult> {
    val functions = nativeFunctions ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not discover native functions.")
        return emptyList()
    }
    return functions.map { it.toBinaryFunctionResult() }
}

private fun NativeFunctionRecord.toBinaryFunctionResult(): BinaryFunctionResult =
    BinaryFunctionResult(
        symbolIndex = symbolIndex,
        source = source.name,
        name = demangledName ?: name,
        demangledName = demangledName,
        mangledName = name.takeIf { demangledName != null && demangledName != name },
        imported = imported,
        thunkLike = thunkLike,
        section = sectionName,
        virtualAddress = virtualAddress.toLongOrNull(),
        fileOffset = fileOffset,
        size = size?.toLongOrNull(),
        rangeConfidence = rangeConfidence.name,
    )

private fun List<BinaryFunctionResult>.filterByTerms(terms: List<String>): List<BinaryFunctionResult> {
    if (terms.isEmpty()) return this
    val loweredTerms = terms.map { it.lowercase() }
    return filter { function ->
        val haystack = function.searchableText()
        loweredTerms.all { it in haystack }
    }
}

private fun BinaryFunctionResult.searchableText(): String =
    listOfNotNull(
        funcidx?.toString(),
        symbolIndex?.toString(),
        source,
        name,
        demangledName,
        mangledName,
        imported.toString(),
        thunkLike?.toString(),
        importModule,
        importName,
        typeidx?.toString(),
        section,
        virtualAddress?.toString(),
        virtualAddress?.let(::hex),
        fileOffset?.toString(),
        size?.toString(),
        rangeConfidence,
    ).joinToString("\u0000").lowercase()

private fun hex(value: Long): String =
    "0x" + value.toString(16)

private fun ULong.toLongOrNull(): Long? =
    if (this <= Long.MAX_VALUE.toULong()) toLong() else null
