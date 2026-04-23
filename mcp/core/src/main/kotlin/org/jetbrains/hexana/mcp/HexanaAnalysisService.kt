package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.jetbrains.hexana.mcp.query.*
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.extension

interface HexanaAnalysisService {
    fun summarizeModule(input: WasmInput): SummarizeModuleResult
    fun listImports(input: WasmInput): ListImportsResult
    fun listExports(input: WasmInput): ListExportsResult
    fun listFunctions(input: WasmInput, offset: Int = 0, limit: Int = 200): FunctionsResult
    fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult
    fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult
    fun listGlobals(input: WasmInput): ListGlobalsResult
    fun listTypes(input: WasmInput): TypesResult
    fun listMemory(input: WasmInput): ListMemoryResult
    fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult
    fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult
    fun listDataSegments(
        input: WasmInput,
        segmentStartInclusive: Int,
        segmentEndExclusive: Int = Int.MAX_VALUE,
        includeExports: Boolean = false,
        includeGlobals: Boolean = true,
        minByteLength: Int? = null
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
    private val parser: WasmParser = WasmParser(),
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    private val maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES
) : HexanaAnalysisService {

    override fun summarizeModule(input: WasmInput): SummarizeModuleResult {
        val wasm = load(input)
        return wasm.buffer.readModuleSummary(wasm.module)
    }

    override fun listImports(input: WasmInput): ListImportsResult {
        val wasm = load(input)
        return wasm.buffer.readImportFunctions(wasm.module)
    }

    override fun listExports(input: WasmInput): ListExportsResult {
        val wasm = load(input)
        return wasm.buffer.readExports(wasm.module)
    }

    override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult {
        requireOffset(offset)
        requireLimit(limit)
        val wasm = load(input)
        return wasm.buffer.readFunctions(wasm.module, offset, limit)
    }

    override fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult {
        requireClosedRange(entriesRange)
        val wasm = load(input)
        return wasm.buffer.readExportedFunctions(wasm.module, entriesRange)
    }

    override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.buffer.readFunctionsByIndices(wasm.module, indices)
    }

    override fun listGlobals(input: WasmInput): ListGlobalsResult {
        val wasm = load(input)
        return wasm.buffer.readGlobals(wasm.module)
    }

    override fun listTypes(input: WasmInput): TypesResult {
        val wasm = load(input)
        return wasm.buffer.readTypes(wasm.module)
    }

    override fun listMemory(input: WasmInput): ListMemoryResult {
        val wasm = load(input)
        return wasm.buffer.readMemory(wasm.module)
    }

    override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.buffer.readGlobalsByIndices(wasm.module, indices)
    }

    override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult {
        requireClosedRange(entriesRange)
        val wasm = load(input)
        return wasm.buffer.readDataSections(wasm.module, entriesRange)
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
        return wasm.buffer.readDataSegments(
            segmentStartInclusive = segmentStartInclusive,
            segmentEndExclusive = segmentEndExclusive,
            wasmModule = wasm.module,
            includeExports = includeExports,
            includeGlobals = includeGlobals,
            minByteLength = minByteLength
        )
    }

    override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.buffer.readMemoryByIndices(wasm.module, indices)
    }

    override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.buffer.readTypesByIndices(wasm.module, indices)
    }

    override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.buffer.readLocalsForFunctions(wasm.module, indices)
    }

    override fun getInstructionsForFunctions(input: WasmInput, indices: List<Int>): GetInstructionsForFunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.buffer.readInstructionsForFunctions(wasm.module, indices)
    }

    override fun listElementSegments(input: WasmInput): ListElementSegmentsResult {
        val wasm = load(input)
        return wasm.buffer.readElementSegments(wasm.module)
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
        val provider = bytes.toCommonByteBuffer().loadCoreProviderOrThrow()
        return LoadedWasm(
            provider = provider,
            module = provider.wasmModule ?: throw wasmParseFailed()
        )
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

    private data class LoadedWasm(
        private val provider: WasmBinaryProvider,
        val module: WasmModule
    ) {
        val buffer: CommonByteBuffer
            get() = provider.bytes
    }

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
