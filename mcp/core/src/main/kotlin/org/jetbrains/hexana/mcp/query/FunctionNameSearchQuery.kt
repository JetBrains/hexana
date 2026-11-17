package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.model.functions.FunctionNameSearchSignatureResult
import org.jetbrains.hexana.mcp.model.functions.FunctionNameSearchResult
import org.jetbrains.hexana.mcp.model.functions.SearchFunctionsByNameResult
import org.jetbrains.hexana.mcp.renderedMcpType
import org.jetbrains.hexana.mcp.serializeLocals
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readFunctionsByName(query: String, limit: Int): SearchFunctionsByNameResult {
    val normalizedQuery = query.lowercase()
    val debugNames = nameSection?.functionSubsection?.namemap.orEmpty()
    return SearchFunctionsByNameResult(
        functions = listFunctionEntries()
            .asSequence()
            .flatMap { entry -> entry.nameCandidates(debugNames[entry.funcidx]).asSequence() }
            .filter { candidate -> candidate.name.lowercase().contains(normalizedQuery) }
            .distinctBy { candidate ->
                listOf(
                    candidate.entry.funcidx.toString(),
                    candidate.name,
                    candidate.kind,
                    candidate.module.orEmpty(),
                    candidate.importName.orEmpty(),
                ).joinToString("\u0000")
            }
            .take(limit)
            .map { it.toResult(this) }
            .toList()
    )
}

private fun GeneralFunctionEntry.nameCandidates(debugName: String?): List<FunctionNameCandidate> {
    val importCandidate = import?.let {
        FunctionNameCandidate(
            entry = this,
            name = it.name,
            kind = "import",
            module = it.module,
            importName = it.name,
        )
    }
    val exportCandidates = exportedAs.map { exportName ->
        FunctionNameCandidate(
            entry = this,
            name = exportName,
            kind = "export",
            exportName = exportName,
        )
    }
    val debugCandidate = debugName?.let {
        FunctionNameCandidate(
            entry = this,
            name = it,
            kind = when {
                import != null -> "import"
                exportedAs.isNotEmpty() -> "export"
                else -> "internal"
            },
        )
    }
    return buildList {
        importCandidate?.let(::add)
        addAll(exportCandidates)
        debugCandidate?.let(::add)
    }
}

private fun FunctionNameCandidate.toResult(provider: WasmBinaryProvider): FunctionNameSearchResult {
    val codeRecord = if (entry.import == null) {
        provider.getCodeRecordByIndex(entry.funcidx - provider.importFuncCount)
    } else {
        null
    }
    return FunctionNameSearchResult(
        funcidx = entry.funcidx,
        name = name,
        kind = kind,
        module = module,
        importName = importName,
        exportName = exportName,
        signature = entry.signature?.toFunctionSignatureResult(),
        locals = codeRecord?.locals.localsAsRenderedTypes(),
        repr = entry.renderFunctionRepr(name, kind, module, importName, exportName),
        offset = entry.codeOffset
    )
}

private fun List<org.jetbrains.hexana.wasm.sections.WasmCodeLocalRecord>?.localsAsRenderedTypes(): List<String> {
    return serializeLocals().map { it.renderedMcpType() }
}

private fun GeneralFunctionEntry.renderFunctionRepr(
    name: String,
    kind: String,
    module: String?,
    importName: String?,
    exportName: String?
): String {
    val params = signature.paramsRepr()
    val results = signature.resultsRepr()
    return when (kind) {
        "import" -> """(import "${module.orEmpty()}" "${importName ?: name}" (func ${symbol(name)}$params$results))"""
        "export" -> """(func ${symbol(name)}$params$results (export "${exportName ?: name}") ...)"""
        else -> "(func ${symbol(name)}$params$results ...)"
    }
}

private fun ParameterSignature?.paramsRepr(): String {
    val params = this?.params.orEmpty()
    return params.joinToString(separator = "") { " (param $it)" }
}

private fun ParameterSignature?.resultsRepr(): String {
    val results = this?.ret.orEmpty()
    return results.joinToString(separator = "") { " (result $it)" }
}

private fun ParameterSignature.toFunctionSignatureResult(): FunctionNameSearchSignatureResult {
    return FunctionNameSearchSignatureResult(
        params = params,
        results = ret
    )
}

private fun symbol(name: String): String {
    return "${'$'}${name.replace(Regex("\\s+"), "_")}"
}

private data class FunctionNameCandidate(
    val entry: GeneralFunctionEntry,
    val name: String,
    val kind: String,
    val module: String? = null,
    val importName: String? = null,
    val exportName: String? = null,
)
