package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSignatureResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readFunctionSignature(funcidx: Int): ResolveFunctionSignatureResult {
    val entry = listFunctionEntries().singleOrNull { it.funcidx == funcidx }
        ?: throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.OUT_OF_RANGE,
            "Function index not found"
        )

    return entry.toResolveFunctionSignatureResult()
}

private fun GeneralFunctionEntry.toResolveFunctionSignatureResult(): ResolveFunctionSignatureResult {
    return ResolveFunctionSignatureResult(
        funcidx = funcidx,
        typeidx = typeidx,
        params = signature?.params.orEmpty(),
        results = signature?.ret.orEmpty(),
        kind = when {
            import != null -> "import"
            exportedAs.isNotEmpty() -> "export"
            else -> "internal"
        },
        module = import?.module,
        importName = import?.name,
        exportNames = exportedAs
    )
}
