package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult

internal fun renderSummarizeModuleJson(
    command: HexanaCommand.SummarizeModule,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): String {
    require(command.format == HexanaCliOutputFormat.JSON) {
        "summarize-module currently supports only json output"
    }
    val result = analysisService.summarizeModule(WasmInput.FilePath(command.path))
    return HexanaJson.compact.encodeToString(SummarizeModuleResult.serializer(), result)
}
