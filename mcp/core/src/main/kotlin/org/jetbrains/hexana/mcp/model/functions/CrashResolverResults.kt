package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WasmFrameInput(
    val funcidx: Int,
    val codeOffset: Long? = null,
    val module: String? = null,
    val label: String? = null,
)

@Serializable
data class SourceLocationInput(
    val funcidx: Int? = null,
    val codeOffset: Long? = null,
)

@Serializable
data class ResolveWasmFramesResult(
    val frames: List<ResolvedWasmFrameResult>,
    val crashReport: CrashReportMetadataResult? = null,
    val sourceMapPath: String? = null,
    val topApplicationFrame: ResolvedWasmFrameResult? = null,
    val applicationFrameSummary: String? = null,
    val reportSummary: String? = null,
    val frameHighlights: WasmFrameHighlightsResult = WasmFrameHighlightsResult(),
    val skippedFrames: List<SkippedWasmFrameResult> = emptyList(),
)

@Serializable
data class WasmFrameHighlightsResult(
    val topApplicationFrame: WasmFrameHighlightResult? = null,
    val callerApplicationFrame: WasmFrameHighlightResult? = null,
    val callerExportFrame: WasmFrameHighlightResult? = null,
    val applicationFrames: List<WasmFrameHighlightResult> = emptyList(),
    val importFrames: List<WasmFrameHighlightResult> = emptyList(),
    val fallbackFrames: List<WasmFrameHighlightResult> = emptyList(),
)

@Serializable
data class WasmFrameHighlightResult(
    val frameIndex: Int,
    val funcidx: Int,
    val origin: String,
    val symbol: String,
    val symbolSource: String,
    val exportedAs: List<String> = emptyList(),
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val sourceLocation: FunctionSourceLocationResult? = null,
    val reason: String,
)

@Serializable
data class CrashReportMetadataResult(
    val path: String? = null,
    val incidentId: String? = null,
    val crashEventId: String? = null,
    val buildId: String? = null,
    val module: String? = null,
    val frameCount: Int,
)

@Serializable
data class SkippedWasmFrameResult(
    val frameIndex: Int,
    val funcidx: Int,
    val origin: String,
    val reason: String,
)

@Serializable
data class ResolvedWasmFrameResult(
    val frameIndex: Int,
    val funcidx: Int,
    val inputCodeOffset: Long? = null,
    val module: String? = null,
    val rawLabel: String? = null,
    val origin: String,
    val symbol: String,
    val symbolSource: String,
    val signature: FunctionSignatureResult? = null,
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val exportedAs: List<String> = emptyList(),
    val codeRange: FunctionCodeRangeResult? = null,
    val sourceLocation: FunctionSourceLocationResult? = null,
    val sourceFallbackReason: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ResolveFunctionSymbolsResult(
    val symbols: List<ResolvedFunctionSymbolResult>,
)

@Serializable
data class ResolvedFunctionSymbolResult(
    val funcidx: Int,
    val origin: String,
    val symbol: String,
    val symbolSource: String,
    val typeidx: Int? = null,
    val signature: FunctionSignatureResult? = null,
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val exportedAs: List<String> = emptyList(),
    val codeRange: FunctionCodeRangeResult? = null,
    val sourceLocation: FunctionSourceLocationResult? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ResolveSourceLocationsResult(
    val locations: List<ResolvedSourceLocationResult>,
)

@Serializable
data class ResolvedSourceLocationResult(
    val queryIndex: Int,
    val funcidx: Int? = null,
    val codeOffset: Long? = null,
    val codeRange: FunctionCodeRangeResult? = null,
    val sourceLocation: FunctionSourceLocationResult? = null,
    val source: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class FunctionCodeRangeResult(
    val offset: Long,
    val size: Long,
)

@Serializable
data class FunctionSourceLocationResult(
    val file: String,
    val line: Int,
    val column: Int,
)
