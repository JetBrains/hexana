package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.sections.CustomSectionResult
import org.jetbrains.hexana.mcp.model.sections.ListCustomSectionsResult
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom

private const val CUSTOM_SECTION_PREVIEW_BYTES = 96

fun WasmBinaryProvider.readCustomSections(): ListCustomSectionsResult {
    val wasmModule = wasmModule ?: error("WasmBinaryProvider without parsed wasmModule should not list custom sections")

    return ListCustomSectionsResult(
        customSections = wasmModule.customSections.map { section ->
            section.previewRecord(bytes).let { preview ->
                CustomSectionResult(section.rawName, section.sectionOffset.toLong(), section.sectionSize.toLong(),
                    preview.encoding, preview.value)
            }
        }
    )
}

data class AuditCustomSection(
    val name: String,
    val offsetBytes: Long,
    val sizeBytes: Long,
    val encoding: String,
    val preview: String,
    val capturedBytes: Int,
    val truncated: Boolean,
)

internal fun readAuditCustomSections(
    module: BaseModule,
    bytes: CommonByteBuffer,
    limit: Int,
): List<AuditCustomSection> =
    module.customSections.take(limit).map { section ->
        val preview = section.previewRecord(bytes)
        AuditCustomSection(section.rawName, section.sectionOffset.toLong(), section.sectionSize.toLong(),
            preview.encoding, preview.value, preview.capturedBytes, section.contentSize > preview.capturedBytes)
    }.sortedWith(compareBy<AuditCustomSection> { it.offsetBytes }.thenBy { it.name })

private fun WasmSectionCustom.previewRecord(bytes: CommonByteBuffer): CustomSectionPreview {
    val capturedBytes = minOf(contentSize, CUSTOM_SECTION_PREVIEW_BYTES)
    val previewBytes = bytes.readBytes(contentOffset, capturedBytes)
    return previewBytes.renderPreview(contentSize > capturedBytes).copy(capturedBytes = capturedBytes)
}

private fun CommonByteBuffer.readBytes(offset: Int, size: Int): ByteArray {
    return ByteArray(size) { index -> this[offset + index] }
}

private data class CustomSectionPreview(
    val encoding: String,
    val value: String,
    val capturedBytes: Int = 0,
)

private fun ByteArray.renderPreview(truncated: Boolean): CustomSectionPreview {
    val utf8 = decodeUtf8TextPrefix(truncated)
    if (utf8 != null) {
        return CustomSectionPreview(encoding = "utf8", value = utf8)
    }

    return CustomSectionPreview(
        encoding = "binary",
        value = joinToString(" ") { it.hexByte() }
    )
}

private fun ByteArray.decodeUtf8TextPrefix(truncated: Boolean): String? {
    val full = runCatching { decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    if (full != null) return full.takeIf { it.isTextLike() }
    if (!truncated) return null
    val endIndex = incompleteUtf8SuffixStart() ?: return null
    return runCatching { decodeToString(endIndex = endIndex, throwOnInvalidSequence = true) }
        .getOrNull()?.takeIf { it.isTextLike() }
}

private fun ByteArray.incompleteUtf8SuffixStart(): Int? {
    for (start in maxOf(size - 3, 0) until size) {
        val lead = this[start].toInt() and 0xff
        val expectedBytes = when (lead) {
            in 0xc2..0xdf -> 2
            in 0xe0..0xef -> 3
            in 0xf0..0xf4 -> 4
            else -> continue
        }
        if (size - start >= expectedBytes) continue
        if ((start + 1 until size).all { index ->
                val value = this[index].toInt() and 0xff
                val range = if (index == start + 1) when (lead) {
                    0xe0 -> 0xa0..0xbf
                    0xed -> 0x80..0x9f
                    0xf0 -> 0x90..0xbf
                    0xf4 -> 0x80..0x8f
                    else -> 0x80..0xbf
                } else {
                    0x80..0xbf
                }
                value in range
            }) return start
    }
    return null
}

private fun String.isTextLike(): Boolean {
    return all { char ->
        char == '\n' || char == '\r' || char == '\t' || !char.isISOControl()
    }
}

private fun Byte.hexByte(): String {
    val value = toInt() and 0xFF
    return "0x${HEX_CHARS[value ushr 4]}${HEX_CHARS[value and 0x0F]}"
}

private const val HEX_CHARS = "0123456789abcdef"
