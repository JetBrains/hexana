/**
 * Hand-written helpers consumed by the generated `HexanaToolset.kt`.
 *
 * This file is NOT regenerated. The `:idea-plugin:generateHexanaToolset` task only writes
 * `src/main/gen/.../HexanaToolset.kt`; if you delete or rename anything here, update the
 * call sites emitted by `mcp/tools-generator/.../IdeaToolsetMain.kt` (see the `MemberName`
 * constants at the bottom of that file) in the same change.
 */
package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import java.util.Base64

/**
 * Shared helpers consumed by [HexanaToolset]. Kept hand-written because
 * they wrap IntelliJ VirtualFile resolution and `mcpFail` — behaviour, not shape.
 */

internal fun VirtualFile.requireCoreWasmBinaryProvider(): WasmBinaryProvider {
    val buffer = contentsToByteArray().toCommonByteBuffer()
    if (WasmBinaryProviderSelector.select(buffer) !is BinaryProviderType.Wasm) {
        mcpFail("Failed to parse .wasm file")
    }
    return WasmBinaryProvider(buffer).takeIf(WasmBinaryProvider::canProvideWasmModule)
        ?: mcpFail("Failed to parse .wasm file")
}

internal fun VirtualFile.requireComponentWasmBinaryProvider(): WasmComponentBinaryProvider {
    val buffer = contentsToByteArray().toCommonByteBuffer()
    if (WasmBinaryProviderSelector.select(buffer) != BinaryProviderType.WasmComponent) {
        mcpFail("Failed to parse .wasm file")
    }
    return WasmComponentBinaryProvider(buffer).takeIf(WasmComponentBinaryProvider::canProvideWasmModule)
        ?: mcpFail("Failed to parse .wasm file")
}

internal data class BinaryProviderInput(
    val provider: BinaryProvider,
    val pathLabel: String?,
)

internal fun requireBinaryProviderInput(
    path: String?,
    bytesBase64: String?,
    bytes: List<Int>?,
): BinaryProviderInput {
    val providedInputs = listOf(path, bytesBase64, bytes).count { it != null }
    if (providedInputs == 0) {
        mcpFail("Exactly one of `path`, `bytesBase64`, or `bytes` is required")
    }
    if (providedInputs > 1) {
        mcpFail("Provide exactly one of `path`, `bytesBase64`, or `bytes`")
    }

    return when {
        path != null -> {
            if (path.isBlank()) {
                mcpFail("`path` must not be blank")
            }
            val file = requireBinaryFile(path)
            val buffer = file.contentsToByteArray().toCommonByteBuffer()
            BinaryProviderInput(buffer.toBinaryProvider(path), path)
        }
        bytesBase64 != null -> {
            if (bytesBase64.isBlank()) {
                mcpFail("`bytesBase64` must not be blank")
            }
            val nonWhitespaceLen = bytesBase64.count { !it.isWhitespace() }
            val upperBoundDecoded = (nonWhitespaceLen.toLong() * 3L + 3L) / 4L
            if (upperBoundDecoded > HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES.toLong()) {
                mcpFail("`bytesBase64` exceeds maximum decoded size of ${HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES} bytes")
            }
            val decoded = try {
                Base64.getMimeDecoder().decode(bytesBase64)
            } catch (_: IllegalArgumentException) {
                mcpFail("`bytesBase64` must be valid base64")
            }
            if (decoded.size > HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES) {
                mcpFail("`bytesBase64` exceeds maximum decoded size of ${HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES} bytes")
            }
            BinaryProviderInput(decoded.toCommonByteBuffer().toBinaryProvider(pathLabel = null), null)
        }
        bytes != null -> {
            if (bytes.size > HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES) {
                mcpFail("`bytes` exceeds maximum size of ${HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES} bytes")
            }
            val rawBytes = ByteArray(bytes.size) { index ->
                val value = bytes[index]
                if (value !in 0..255) {
                    mcpFail("`bytes[$index]` must be between 0 and 255")
                }
                value.toByte()
            }
            BinaryProviderInput(rawBytes.toCommonByteBuffer().toBinaryProvider(pathLabel = null), null)
        }
        else -> error("Unreachable binary input state")
    }
}

internal fun requireWasmFile(path: String): VirtualFile {
    val file = resolveWasmVirtualFile(path) ?: mcpFail("File not found")
    if (file.fileType != WasmFileType.INSTANCE) {
        mcpFail("Not a .wasm file")
    }
    return file
}

private fun requireBinaryFile(path: String): VirtualFile {
    val file = resolveProjectVirtualFile(path) ?: mcpFail("File not found")
    if (!file.isValid || file.isDirectory) {
        mcpFail("File not found")
    }
    return file
}

private fun org.jetbrains.hexana.io.CommonByteBuffer.toBinaryProvider(pathLabel: String?): BinaryProvider {
    position(0)
    return when (WasmBinaryProviderSelector.select(this)) {
        BinaryProviderType.Wasm -> WasmBinaryProvider(this)
        BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(this)
        BinaryProviderType.Generic -> nativeOrGenericBinaryProvider(this, pathLabel)
    }
}

internal fun requireTextFile(path: String): String {
    val file = resolveProjectVirtualFile(path) ?: mcpFail("File not found")
    if (!file.isValid || file.isDirectory) {
        mcpFail("File not found")
    }
    return file.contentsToByteArray().toString(Charsets.UTF_8)
}

internal fun requireClosedRange(entriesRange: Map<String, Int>, fieldName: String): IntRange {
    val from = entriesRange["from"] ?: mcpFail("Invalid $fieldName: missing `from`")
    val to = entriesRange["to"] ?: mcpFail("Invalid $fieldName: missing `to`")
    if (to < from) {
        mcpFail("Invalid $fieldName: `to` must be >= `from`")
    }
    return from..to
}

internal fun requireHalfOpenSegmentRange(segmentRange: Map<String, Int>): IntRange {
    val from = segmentRange["from"] ?: mcpFail("Invalid segmentRange no `from`")
    val toExclusive = segmentRange["to"] ?: Int.MAX_VALUE
    if (from > toExclusive) {
        mcpFail("Invalid segmentRange `to` must be >= `from`")
    }
    return from until toExclusive
}
