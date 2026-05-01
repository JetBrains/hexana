package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.sections.CustomSectionResult
import org.jetbrains.hexana.mcp.model.sections.ListCustomSectionsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom

private const val CUSTOM_SECTION_PREVIEW_BYTES = 96

fun WasmBinaryProvider.readCustomSections(): ListCustomSectionsResult {
    val wasmModule = wasmModule ?: error("WasmBinaryProvider without parsed wasmModule should not list custom sections")

    return ListCustomSectionsResult(
        customSections = wasmModule.customSections.map { section ->
            section.toCustomSectionResult(this)
        }
    )
}

private fun WasmSectionCustom.toCustomSectionResult(provider: WasmBinaryProvider): CustomSectionResult {
    val previewBytes = provider.readBytes(contentOffset, minOf(contentSize, CUSTOM_SECTION_PREVIEW_BYTES))
    val preview = previewBytes.renderPreview()

    return CustomSectionResult(
        name = rawName,
        offset = sectionOffset.toLong(),
        size = sectionSize.toLong(),
        encoding = preview.encoding,
        preview = preview.value
    )
}

private fun WasmBinaryProvider.readBytes(offset: Int, size: Int): ByteArray {
    val buffer = bytes
    return ByteArray(size) { index -> buffer[offset + index] }
}

private data class CustomSectionPreview(
    val encoding: String,
    val value: String
)

private fun ByteArray.renderPreview(): CustomSectionPreview {
    val utf8 = decodeUtf8TextPrefix()
    if (utf8 != null) {
        return CustomSectionPreview(encoding = "utf8", value = utf8)
    }

    return CustomSectionPreview(
        encoding = "binary",
        value = joinToString(" ") { it.hexByte() }
    )
}

private fun ByteArray.decodeUtf8TextPrefix(): String? {
    for (endIndex in size downTo maxOf(size - 3, 0)) {
        val decoded = runCatching {
            decodeToString(endIndex = endIndex, throwOnInvalidSequence = true)
        }.getOrNull()
        if (decoded != null && decoded.isTextLike()) {
            return decoded
        }
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
