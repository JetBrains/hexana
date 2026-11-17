package org.jetbrains.hexana.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.extension

class LoadedWasmBytes(bytes: ByteArray) {
    private val stableBytes = bytes.copyOf()

    val bytes: ByteArray
        get() = stableBytes.copyOf()
}

class WasmInputBytesLoader(
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    private val maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
) {
    private var fileSizeReader: (Path) -> Long = Files::size
    private var fileBytesReader: (Path) -> BoundedFileReadResult = { readFileBytesBounded(it, maxFileInputBytes) }

    internal constructor(
        maxInlineInputBytes: Int,
        maxFileInputBytes: Int,
        allowedFilePolicy: AllowedFilePolicy,
        fileSizeReader: (Path) -> Long,
        fileBytesReader: (Path) -> ByteArray,
    ) : this(maxInlineInputBytes, maxFileInputBytes, allowedFilePolicy) {
        this.fileSizeReader = fileSizeReader
        this.fileBytesReader = { path -> fileBytesReader(path).toBoundedFileReadResult(maxFileInputBytes) }
    }

    fun load(input: WasmInput): LoadedWasmBytes {
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

    private fun loadFromPath(rawPath: String): LoadedWasmBytes {
        val path = allowedFilePolicy.resolveAllowedRegularFile(rawPath, ".wasm file")
        if (path.extension.lowercase() != "wasm") {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.NOT_WASM, "Not a .wasm file")
        }
        requireFileByteCount(path)

        val readResult = try {
            fileBytesReader(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read .wasm file",
                error
            )
        }
        val bytes = when (readResult) {
            is BoundedFileReadResult.Bytes -> readResult.bytes
            is BoundedFileReadResult.TooLarge -> throw fileTooLarge()
        }
        return LoadedWasmBytes(bytes)
    }

    private fun loadFromInlineBytes(base64: String): LoadedWasmBytes {
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
        return LoadedWasmBytes(bytes)
    }

    private fun loadFromRawBytes(bytes: ByteArray): LoadedWasmBytes {
        requireInlineByteCount(bytes.size)
        return LoadedWasmBytes(bytes)
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
            fileSizeReader(path)
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
            HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
            "File exceeds maximum size of $maxFileInputBytes bytes. Use a smaller file or increase the configured MCP file-size limit."
        )
    }

    private fun inlineBytesTooLarge(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
            "Inline bytes exceed maximum size of $maxInlineInputBytes bytes. Send a smaller inline payload or increase the configured MCP inline-size limit."
        )
    }
}

fun WasmInput.loadWasmBytes(
    maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
): LoadedWasmBytes = WasmInputBytesLoader(maxInlineInputBytes, maxFileInputBytes, allowedFilePolicy).load(this)
