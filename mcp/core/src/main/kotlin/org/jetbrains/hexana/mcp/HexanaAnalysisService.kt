package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.artifacts.QueryArtifactsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.exports.SearchExportsResult
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.DisassembleFunctionResult
import org.jetbrains.hexana.mcp.model.functions.GetFunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.ListCodeSectionOffsetsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.SearchFunctionsByNameResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.runtime.CheckRuntimeCompatibilityResult
import org.jetbrains.hexana.mcp.model.runtime.DiffRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.runtime.ListRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.sections.ListCustomSectionsResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.ListReferenceTypesResult
import org.jetbrains.hexana.mcp.model.types.ListValueTypesResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.jetbrains.hexana.mcp.query.*
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.extension

interface HexanaAnalysisService {
    fun queryArtifacts(
        input: WasmInput,
        baselineInput: WasmInput?,
        operation: String,
        entityTypes: List<String>,
        query: List<String>,
        startName: String?,
        startFuncidx: Int?,
        relations: List<String>,
        limit: Int,
        detail: String,
        maxBytes: Int
    ): QueryArtifactsResult {
        throw UnsupportedOperationException("queryArtifacts is not implemented")
    }

    fun summarizeModule(input: WasmInput): SummarizeModuleResult
    fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult
    fun listRequiredProposals(input: WasmInput): ListRequiredProposalsResult {
        throw UnsupportedOperationException("listRequiredProposals is not implemented")
    }
    fun diffRequiredProposals(input: WasmInput, baselineInput: WasmInput): DiffRequiredProposalsResult {
        throw UnsupportedOperationException("diffRequiredProposals is not implemented")
    }
    fun checkRuntimeCompatibility(input: WasmInput, runtime: String, version: String?): CheckRuntimeCompatibilityResult {
        throw UnsupportedOperationException("checkRuntimeCompatibility is not implemented")
    }
    fun listCustomSections(input: WasmInput): ListCustomSectionsResult
    fun listImports(input: WasmInput): ListImportsResult
    fun listExports(input: WasmInput): ListExportsResult
    fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult
    fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult
    fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult
    fun resolveFunctionSignature(input: WasmInput, funcidx: Int): ResolveFunctionSignatureResult
    fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult
    fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult
    fun searchFunctionsByName(input: WasmInput, query: String, limit: Int): SearchFunctionsByNameResult
    fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult
    fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult
    fun listGlobals(input: WasmInput): ListGlobalsResult
    fun listTypes(input: WasmInput): TypesResult
    fun listValueTypes(input: WasmInput): ListValueTypesResult
    fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult
    fun listMemory(input: WasmInput): ListMemoryResult
    fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult
    fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult
    fun listDataSegments(
        input: WasmInput,
        segmentStartInclusive: Int,
        segmentEndExclusive: Int,
        includeExports: Boolean,
        includeGlobals: Boolean,
        minByteLength: Int?
    ): ListDataSegmentsResult

    fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult
    fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult
    fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult
    fun getInstructionsForFunctions(input: WasmInput, indices: List<Int>): GetInstructionsForFunctionsResult
    fun listElementSegments(input: WasmInput): ListElementSegmentsResult
}

object HexanaAnalysisServices {
    val default: HexanaAnalysisService = DefaultHexanaAnalysisService()
}

class DefaultHexanaAnalysisService(
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    private val maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES
) : HexanaAnalysisService {

    override fun queryArtifacts(
        input: WasmInput,
        baselineInput: WasmInput?,
        operation: String,
        entityTypes: List<String>,
        query: List<String>,
        startName: String?,
        startFuncidx: Int?,
        relations: List<String>,
        limit: Int,
        detail: String,
        maxBytes: Int
    ): QueryArtifactsResult {
        val wasm = load(input)
        val baseline = baselineInput?.let(::load)
        return wasm.provider.readArtifactQuery(
            baseline = baseline?.provider,
            operation = operation,
            entityTypes = entityTypes,
            query = query,
            startName = startName,
            startFuncidx = startFuncidx,
            relations = relations,
            limit = limit,
            detail = detail,
            maxBytes = maxBytes,
            pathLabel = input.artifactPathLabel(),
            baselinePathLabel = baselineInput?.artifactPathLabel()
        )
    }

    override fun summarizeModule(input: WasmInput): SummarizeModuleResult {
        val wasm = load(input)
        return wasm.provider.readModuleSummary()
    }

    override fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult {
        val wasm = load(input)
        return wasm.provider.readEnabledFeatures()
    }

    override fun listRequiredProposals(input: WasmInput): ListRequiredProposalsResult {
        val wasm = load(input)
        return wasm.provider.readRequiredProposals()
    }

    override fun diffRequiredProposals(input: WasmInput, baselineInput: WasmInput): DiffRequiredProposalsResult {
        val wasm = load(input)
        val baseline = load(baselineInput)
        return wasm.provider.diffRequiredProposals(
            baseline = baseline.provider,
            pathLabel = input.artifactPathLabel(),
            baselinePathLabel = baselineInput.artifactPathLabel(),
        )
    }

    override fun checkRuntimeCompatibility(
        input: WasmInput,
        runtime: String,
        version: String?
    ): CheckRuntimeCompatibilityResult {
        requireNonBlank(runtime, "Runtime")
        val wasm = load(input)
        return wasm.provider.readRuntimeCompatibility(runtime, version)
    }

    override fun listCustomSections(input: WasmInput): ListCustomSectionsResult {
        val wasm = load(input)
        return wasm.provider.readCustomSections()
    }

    override fun listImports(input: WasmInput): ListImportsResult {
        val wasm = load(input)
        return wasm.provider.readImportFunctions()
    }

    override fun listExports(input: WasmInput): ListExportsResult {
        val wasm = load(input)
        return wasm.provider.readExports()
    }

    override fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult {
        requireNonEmptyStrings(query, "Query")
        requireLimit(limit)
        val wasm = load(input)
        return wasm.provider.readExportsByName(query, limit)
    }

    override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult {
        requireOffset(offset)
        requireLimit(limit)
        val wasm = load(input)
        return wasm.provider.readFunctions(offset, limit)
    }

    override fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult {
        val wasm = load(input)
        return wasm.provider.readCodeSectionOffsets()
    }

    override fun resolveFunctionSignature(input: WasmInput, funcidx: Int): ResolveFunctionSignatureResult {
        requireIndices(listOf(funcidx))
        val wasm = load(input)
        return wasm.provider.readFunctionSignature(funcidx)
    }

    override fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult {
        requireIndices(listOf(funcidx))
        val wasm = load(input)
        return wasm.provider.readFunctionDisassembly(funcidx)
    }

    override fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readFunctionCallGraph(indices)
    }

    override fun searchFunctionsByName(input: WasmInput, query: String, limit: Int): SearchFunctionsByNameResult {
        requireNonBlank(query, "Query")
        requireLimit(limit)
        val wasm = load(input)
        return wasm.provider.readFunctionsByName(query, limit)
    }

    override fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult {
        requireClosedRange(entriesRange)
        val wasm = load(input)
        return wasm.provider.readExportedFunctions(entriesRange)
    }

    override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readFunctionsByIndices(indices)
    }

    override fun listGlobals(input: WasmInput): ListGlobalsResult {
        val wasm = load(input)
        return wasm.provider.readGlobals()
    }

    override fun listTypes(input: WasmInput): TypesResult {
        val wasm = load(input)
        return wasm.provider.readTypes()
    }

    override fun listValueTypes(input: WasmInput): ListValueTypesResult {
        val wasm = load(input)
        return wasm.provider.readValueTypes()
    }

    override fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult {
        val wasm = load(input)
        return wasm.provider.readReferenceTypes()
    }

    override fun listMemory(input: WasmInput): ListMemoryResult {
        val wasm = load(input)
        return wasm.provider.readMemory()
    }

    override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readGlobalsByIndices(indices)
    }

    override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult {
        requireClosedRange(entriesRange)
        val wasm = load(input)
        return wasm.provider.readDataSections(entriesRange)
    }

    override fun listDataSegments(
        input: WasmInput,
        segmentStartInclusive: Int,
        segmentEndExclusive: Int,
        includeExports: Boolean,
        includeGlobals: Boolean,
        minByteLength: Int?
    ): ListDataSegmentsResult {
        requireHalfOpenRange(segmentStartInclusive, segmentEndExclusive)
        requireOptionalNonNegative(minByteLength, "Minimum byte length")
        val wasm = load(input)
        return wasm.provider.readDataSegments(
            segmentStartInclusive = segmentStartInclusive,
            segmentEndExclusive = segmentEndExclusive,
            includeExports = includeExports,
            includeGlobals = includeGlobals,
            minByteLength = minByteLength
        )
    }

    override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readMemoryByIndices(indices)
    }

    override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readTypesByIndices(indices)
    }

    override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readLocalsForFunctions(indices)
    }

    override fun getInstructionsForFunctions(input: WasmInput, indices: List<Int>): GetInstructionsForFunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readInstructionsForFunctions(indices)
    }

    override fun listElementSegments(input: WasmInput): ListElementSegmentsResult {
        val wasm = load(input)
        return wasm.provider.readElementSegments()
    }

    private fun load(input: WasmInput): LoadedWasm {
        return when (input) {
            is WasmInput.FilePath -> loadFromPath(input.path)
            is WasmInput.Uri -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND,
                "Unsupported input kind: uri"
            )

            is WasmInput.InlineBytes -> loadFromInlineBytes(input.base64)
            is WasmInput.RawBytes -> loadFromRawBytes(input.bytes)
        }
    }

    private fun loadFromPath(rawPath: String): LoadedWasm {
        if (rawPath.isBlank()) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Path must not be blank")
        }

        val path = Path.of(rawPath)
        if (!Files.exists(path)) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.FILE_NOT_FOUND, "File not found")
        }
        if (!Files.isRegularFile(path)) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.FILE_NOT_FOUND, "File not found")
        }
        if (path.extension.lowercase() != "wasm") {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.NOT_WASM, "Not a .wasm file")
        }
        requireFileByteCount(path)

        val bytes = try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read .wasm file",
                error
            )
        }

        return loadFromBytes(bytes)
    }

    private fun loadFromInlineBytes(base64: String): LoadedWasm {
        if (base64.isBlank()) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Inline bytes must not be blank")
        }
        requireInlineBase64Size(base64)

        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "Inline bytes must be valid base64"
            )
        }

        requireInlineByteCount(bytes.size)
        return loadFromBytes(bytes)
    }

    private fun loadFromRawBytes(bytes: ByteArray): LoadedWasm {
        requireInlineByteCount(bytes.size)
        return loadFromBytes(bytes)
    }

    private fun loadFromBytes(bytes: ByteArray): LoadedWasm {
        return LoadedWasm(bytes.toCommonByteBuffer().loadCoreProviderOrThrow())
    }

    private fun requireInlineBase64Size(base64: String) {
        val paddingBytes = base64.takeLastWhile { it == '=' }.length.coerceAtMost(2)
        val estimatedDecodedSize = ((base64.length.toLong() + 3L) / 4L) * 3L - paddingBytes
        if (estimatedDecodedSize > maxInlineInputBytes.toLong()) {
            throw inlineBytesTooLarge()
        }
    }

    private fun requireFileByteCount(path: Path) {
        val size = try {
            Files.size(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read .wasm file",
                error
            )
        }
        if (size > maxFileInputBytes.toLong()) {
            throw fileTooLarge()
        }
    }
    private fun requireInlineByteCount(size: Int) {
        if (size > maxInlineInputBytes) {
            throw inlineBytesTooLarge()
        }
    }

    private fun fileTooLarge(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "File exceeds maximum size of $maxFileInputBytes bytes"
        )
    }
    private fun inlineBytesTooLarge(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "Inline bytes exceed maximum size of $maxInlineInputBytes bytes"
        )
    }
    private fun requireOffset(offset: Int) {
        if (offset < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Offset must be greater than or equal to 0"
            )
        }
    }

    private fun requireLimit(limit: Int) {
        if (limit <= 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Limit must be greater than 0"
            )
        }
    }

    private fun requireNonBlank(value: String, label: String) {
        if (value.isBlank()) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label must not be blank"
            )
        }
    }

    private fun requireNonEmptyStrings(values: List<String>, label: String) {
        if (values.isEmpty()) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label must contain at least one value"
            )
        }
        if (values.any { it.isBlank() }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label values must not be blank"
            )
        }
    }

    private fun requireClosedRange(range: IntRange) {
        if (range.first < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `from` must be greater than or equal to 0"
            )
        }
        if (range.last < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to 0"
            )
        }
        if (range.last < range.first) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to `from`"
            )
        }
    }

    private fun requireHalfOpenRange(from: Int, to: Int) {
        if (from < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `from` must be greater than or equal to 0"
            )
        }
        if (to < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to 0"
            )
        }
        if (to < from) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to `from`"
            )
        }
    }

    private fun requireIndices(indices: List<Int>) {
        if (indices.any { it < 0 }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Indices must be greater than or equal to 0"
            )
        }
    }

    private fun requireOptionalNonNegative(value: Int?, label: String) {
        if (value != null && value < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "$label must be greater than or equal to 0"
            )
        }
    }

    private fun WasmInput.artifactPathLabel(): String? =
        (this as? WasmInput.FilePath)?.path

    private data class LoadedWasm(val provider: WasmBinaryProvider)

    private fun CommonByteBuffer.loadCoreProviderOrThrow(): WasmBinaryProvider {
        if (WasmBinaryProviderSelector.select(this) !is BinaryProviderType.Wasm) {
            throw wasmParseFailed()
        }

        return WasmBinaryProvider(this).takeIf(WasmBinaryProvider::canProvideWasmModule)
            ?: throw wasmParseFailed()
    }

    private fun wasmParseFailed(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.WASM_PARSE_FAILED,
            "Failed to parse .wasm file"
        )
    }
}
