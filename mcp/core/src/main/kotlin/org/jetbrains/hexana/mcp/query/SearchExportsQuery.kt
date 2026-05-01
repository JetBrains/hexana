package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.model.exports.SearchExportFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.exports.SearchExportResult
import org.jetbrains.hexana.mcp.model.exports.SearchExportsResult
import org.jetbrains.hexana.mcp.model.memory.MemoryLimitsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry

fun WasmBinaryProvider.readExportsByName(query: List<String>, limit: Int): SearchExportsResult {
    val terms = query.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val functionsByIndex = listFunctionEntries().associateBy { it.funcidx }
    val memoriesByIndex = readMemory().memories.associateBy { it.memoryIdx }

    return SearchExportsResult(
        results = exports
            .asSequence()
            .mapNotNull { export ->
                val score = terms.mapNotNull { term -> export.name.matchScore(term) }.maxOrNull() ?: return@mapNotNull null
                export.toSearchExportResult(
                    score = score,
                    function = functionsByIndex[export.externIndex],
                    memoryLimit = memoriesByIndex[export.externIndex]?.limit,
                )
            }
            .sortedWith(compareByDescending<SearchExportResult> { it.score }.thenBy { it.name })
            .take(limit)
            .toList()
    )
}

private fun WasmExportRecordEntry.toSearchExportResult(
    score: Double,
    function: GeneralFunctionEntry?,
    memoryLimit: MemoryLimitsResult?,
): SearchExportResult {
    val index = externIndex
    return SearchExportResult(
        name = name,
        kind = kindName,
        externIndex = index,
        funcidx = if (this is WasmFunctionExportRecordEntry) index else null,
        tableidx = if (this is WasmTableExportRecordEntry) index else null,
        memidx = if (this is WasmMemoryExportRecordEntry) index else null,
        globalidx = if (this is WasmGlobalExportRecordEntry) index else null,
        tagidx = if (this is WasmTagExportRecordEntry) index else null,
        signature = function?.signature?.toSearchExportSignatureResult(),
        limits = memoryLimit.takeIf { this is WasmMemoryExportRecordEntry },
        repr = renderExportRepr(index),
        score = score
    )
}

private val WasmExportRecordEntry.externIndex: Int
    get() = when (this) {
        is WasmFunctionExportRecordEntry -> index.toInt()
        is WasmTableExportRecordEntry -> index.toInt()
        is WasmMemoryExportRecordEntry -> index.toInt()
        is WasmGlobalExportRecordEntry -> index.toInt()
        is WasmTagExportRecordEntry -> index.toInt()
        is WasmUnsupportedExportRecordEntry -> index.toInt()
    }

private val WasmExportRecordEntry.kindName: String
    get() = when (this) {
        is WasmFunctionExportRecordEntry -> "func"
        is WasmTableExportRecordEntry -> "table"
        is WasmMemoryExportRecordEntry -> "memory"
        is WasmGlobalExportRecordEntry -> "global"
        is WasmTagExportRecordEntry -> "tag"
        is WasmUnsupportedExportRecordEntry -> "unknown"
    }

private fun WasmExportRecordEntry.renderExportRepr(index: Int): String {
    return """(export "$name" ($kindName $index))"""
}

private fun ParameterSignature.toSearchExportSignatureResult(): SearchExportFunctionSignatureResult {
    return SearchExportFunctionSignatureResult(
        params = params,
        results = ret
    )
}

private fun String.matchScore(term: String): Double? {
    val name = lowercase()
    return when {
        name == term -> 1.0
        name.startsWith(term) -> 0.9
        name.contains(term) -> 0.75
        else -> null
    }
}
