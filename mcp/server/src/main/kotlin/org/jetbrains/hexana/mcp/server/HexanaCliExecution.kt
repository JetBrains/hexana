package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult

internal fun renderSummarizeModuleOutput(
    command: HexanaCommand.SummarizeModule,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): String {
    val result = analysisService.summarizeModule(WasmInput.FilePath(command.path))
    return when (command.format) {
        HexanaCliOutputFormat.JSON -> HexanaJson.compact.encodeToString(SummarizeModuleResult.serializer(), result)
        HexanaCliOutputFormat.PRETTY -> renderSummarizeModulePretty(result)
        HexanaCliOutputFormat.RAW -> error("summarize-module does not support raw output")
    }
}
