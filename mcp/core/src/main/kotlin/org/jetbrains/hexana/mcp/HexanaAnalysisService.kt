package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.query.readExports
import org.jetbrains.hexana.mcp.query.readFunctions
import org.jetbrains.hexana.mcp.query.readImportFunctions
import org.jetbrains.hexana.mcp.query.readModuleSummary
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

interface HexanaAnalysisService {
    fun summarizeModule(input: WasmInput): SummarizeModuleResult
    fun listImports(input: WasmInput): ListImportsResult
    fun listExports(input: WasmInput): ListExportsResult
    fun listFunctions(input: WasmInput, offset: Int = 0, limit: Int = 200): FunctionsResult
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

    private fun load(input: WasmInput): LoadedWasm {
        return when (input) {
            is WasmInput.FilePath -> loadFromPath(input.path)
            is WasmInput.Uri -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND,
                "Unsupported input kind: uri"
            )

            is WasmInput.InlineBytes -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND,
                "Unsupported input kind: inline_bytes"
            )
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

        val buffer = bytes.toCommonByteBuffer()
        val module = parser.parseOrThrow(buffer)
        return LoadedWasm(buffer, module)
    }

    private fun requireInlineBase64Size(base64: String) {
        val estimatedDecodedSize = ((base64.length.toLong() + 3L) / 4L) * 3L
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

    private data class LoadedWasm(
        val buffer: CommonByteBuffer,
        val module: WasmModule
    )

    private fun WasmParser.parseOrThrow(buffer: CommonByteBuffer): WasmModule {
        return when (val result = parse(buffer)) {
            is WasmParseSuccess -> result.wasmModule as? WasmModule
                ?: throw HexanaAnalysisException(
                    HexanaAnalysisErrorCode.WASM_PARSE_FAILED,
                    "Failed to parse .wasm file"
                )

            else -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.WASM_PARSE_FAILED,
                "Failed to parse .wasm file"
            )
        }
    }
}
