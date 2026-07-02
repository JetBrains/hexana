package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

data class LoadedBinary(
    val provider: BinaryProvider,
    val identity: BinaryModuleIdentityResult,
)

class BinaryInputLoader(
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
    private val maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
) {
    fun load(input: BinaryInput): LoadedBinary {
        return when (input) {
            is BinaryInput.FilePath -> loadFromPath(input.path)
            is BinaryInput.InlineBytes -> loadFromInlineBytes(input.base64)
            is BinaryInput.RawBytes -> loadFromRawBytes(input.bytes)
        }
    }

    private fun loadFromPath(rawPath: String): LoadedBinary {
        val path = allowedFilePolicy.resolveAllowedRegularFile(rawPath, "Binary file")
        requireFileByteCount(path)

        val bytes = try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read binary file",
                error
            )
        }

        return loadFromBytes(bytes, pathLabel = rawPath)
    }

    private fun loadFromInlineBytes(base64: String): LoadedBinary {
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
        return loadFromBytes(bytes, pathLabel = null)
    }

    private fun loadFromRawBytes(bytes: ByteArray): LoadedBinary {
        requireInlineByteCount(bytes.size)
        return loadFromBytes(bytes, pathLabel = null)
    }

    private fun loadFromBytes(bytes: ByteArray, pathLabel: String?): LoadedBinary {
        val provider = bytes.toCommonByteBuffer().toBinaryProvider(pathLabel)
        return LoadedBinary(
            provider = provider,
            identity = provider.toModuleIdentity(pathLabel)
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
                "Failed to read binary file",
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
}

fun BinaryInput.loadBinary(
    maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
    maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
    allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
): LoadedBinary = BinaryInputLoader(maxInlineInputBytes, maxFileInputBytes, allowedFilePolicy).load(this)

internal fun CommonByteBuffer.toBinaryProvider(pathLabel: String?): BinaryProvider {
    position(0)
    return when (WasmBinaryProviderSelector.select(this)) {
        BinaryProviderType.Wasm -> WasmBinaryProvider(this)
        BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(this)
        BinaryProviderType.Generic -> nativeOrGenericBinaryProvider(this, pathLabel)
    }
}

internal fun BinaryProvider.toModuleIdentity(pathLabel: String?): BinaryModuleIdentityResult {
    val bytes = bytes
    return when (this) {
        is WasmBinaryProvider -> BinaryModuleIdentityResult(
            path = pathLabel,
            format = "wasm",
            displayFormat = "WebAssembly",
            byteLength = bytes.size,
        )
        is WasmComponentBinaryProvider -> BinaryModuleIdentityResult(
            path = pathLabel,
            format = "wasm_component",
            displayFormat = "WebAssembly component",
            byteLength = bytes.size,
        )
        is NativeBinaryProvider -> BinaryModuleIdentityResult(
            path = pathLabel,
            format = nativeFormat.formatId(),
            displayFormat = nativeFormat.displayName,
            byteLength = bytes.size,
            architecture = nativeFormat.architectureLabel(),
            endian = nativeFormat.endianLabel(),
            wordSize = nativeFormat.wordSizeLabel(),
        )
        is GenericBinaryProvider -> BinaryModuleIdentityResult(
            path = pathLabel,
            format = "generic",
            displayFormat = "Generic binary",
            byteLength = bytes.size,
        )
    }
}

private fun NativeBinaryFormat.formatId(): String =
    when (this) {
        is NativeBinaryFormat.Elf -> "elf"
        is NativeBinaryFormat.MachO -> "mach_o"
        is NativeBinaryFormat.FatMachO -> "fat_mach_o"
        is NativeBinaryFormat.Pe -> "pe"
        is NativeBinaryFormat.Coff -> "coff"
        NativeBinaryFormat.Archive -> "archive"
    }

private fun NativeBinaryFormat.architectureLabel(): String? =
    when (this) {
        is NativeBinaryFormat.Elf -> machine?.let { "machine:$it" }
        is NativeBinaryFormat.MachO -> cpuType?.let { "cpu:$it" }
        is NativeBinaryFormat.Pe -> machine?.let { "machine:$it" }
        is NativeBinaryFormat.Coff -> "machine:$machine"
        is NativeBinaryFormat.FatMachO,
        NativeBinaryFormat.Archive -> null
    }

private fun NativeBinaryFormat.endianLabel(): String? =
    when (this) {
        is NativeBinaryFormat.Elf -> endian.name.lowercase()
        is NativeBinaryFormat.MachO -> endian.name.lowercase()
        is NativeBinaryFormat.FatMachO -> endian.name.lowercase()
        is NativeBinaryFormat.Pe,
        is NativeBinaryFormat.Coff,
        NativeBinaryFormat.Archive -> null
    }

private fun NativeBinaryFormat.wordSizeLabel(): String? {
    val wordSize = when (this) {
        is NativeBinaryFormat.Elf -> wordSize
        is NativeBinaryFormat.MachO -> wordSize
        is NativeBinaryFormat.FatMachO -> wordSize
        is NativeBinaryFormat.Pe -> wordSize
        is NativeBinaryFormat.Coff,
        NativeBinaryFormat.Archive -> null
    }
    return when (wordSize) {
        NativeBinaryWordSize.Bits32 -> "32"
        NativeBinaryWordSize.Bits64 -> "64"
        NativeBinaryWordSize.Unknown -> "unknown"
        null -> null
    }
}
