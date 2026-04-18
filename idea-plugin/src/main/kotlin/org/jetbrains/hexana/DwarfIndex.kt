package org.jetbrains.hexana

import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import org.jetbrains.hexana.dwarf.DwarfLineSection
import org.jetbrains.hexana.dwarf.DwarfLineUnitHeader
import org.jetbrains.hexana.dwarf.DwarfSection
import org.jetbrains.hexana.dwarf.LineNumberFileEntryV4
import org.jetbrains.hexana.dwarf.LineNumberHeaderEntryFormatName
import org.jetbrains.hexana.dwarf.readStringZ
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor

val DwarfIndexId: ID<String, Void?> = ID.create("DwarfIndex")

class DwarfIndex : ScalarIndexExtension<String>() {
    override fun getName() = DwarfIndexId

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == WasmFileType.INSTANCE }

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getVersion(): Int = 1

    override fun getIndexer() = DataIndexer<String, Void?, FileContent> { content ->
        try {
            val buffer = content.content.toCommonByteBuffer()
            val parseResult = WasmParser().parse(buffer) as? WasmParseSuccess ?: return@DataIndexer emptyMap()
            val module = parseResult.wasmModule as? BaseModule ?: return@DataIndexer emptyMap()
            val accessor = WasmDwarfAccessor(module)
            val lineSection = accessor.debugLineSection() ?: return@DataIndexer emptyMap()
            val headers = with(lineSection) { buffer.scanLineTable() }
            val result = mutableMapOf<String, Void?>()
            for (header in headers) {
                for (fileName in buffer.extractFileNames(header, lineSection)) {
                    result[fileName] = null
                }
            }
            result
        } catch (_: Exception) {
            // Silently ignore malformed DWARF data during indexing
            emptyMap()
        }
    }
}

internal fun CommonByteBuffer.extractFileNames(
    header: DwarfLineUnitHeader<*>,
    lineSection: DwarfLineSection
): List<String> {
    return when (header) {
        is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4 ->
            resolveV4FileNames(header.includeDirectories, header.includeFiles)

        is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4 ->
            resolveV4FileNames(header.includeDirectories, header.includeFiles)

        is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5 ->
            resolveV5FileNames(header.fileNames, header.directories, lineSection)

        is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V5 ->
            emptyList()
    }
}

private fun resolveV4FileNames(
    includeDirectories: List<String>,
    includeFiles: List<LineNumberFileEntryV4>
): List<String> {
    return includeFiles.map { fileEntry ->
        val directory = when (val dirIndex = fileEntry.dirIndex) {
            0 -> ""
            else -> includeDirectories.getOrNull(dirIndex - 1) ?: ""
        }
        if (directory.isEmpty()) fileEntry.fileName
        else "${directory}/${fileEntry.fileName}"
    }
}

private fun CommonByteBuffer.resolveV5FileNames(
    fileNames: List<List<DwarfLineSection.Entry>>,
    directories: List<List<DwarfLineSection.Entry>>,
    lineSection: DwarfLineSection
): List<String> {
    val debugLineStr = lineSection.accessor.section(".debug_line_str") ?: return emptyList()
    return fileNames.mapNotNull { fileEntry ->
        val directoryIndex = fileEntry
            .find { it.formatName == LineNumberHeaderEntryFormatName.DW_LNCT_directory_index }
            ?.let { it.value as? Long }
        val fileNameValue = fileEntry
            .find { it.formatName == LineNumberHeaderEntryFormatName.DW_LNCT_path }
            ?.value

        val fileName = resolveStringValue(fileNameValue, debugLineStr) ?: return@mapNotNull null
        val directoryName = directoryIndex?.let { idx ->
            val directoryEntry = directories.getOrNull(idx.toInt()) ?: return@let null
            val dirPathValue = directoryEntry
                .find { it.formatName == LineNumberHeaderEntryFormatName.DW_LNCT_path }
                ?.value
            resolveStringValue(dirPathValue, debugLineStr)
        }

        if (directoryName.isNullOrEmpty()) fileName
        else "${directoryName}/${fileName}"
    }
}

private fun CommonByteBuffer.resolveStringValue(
    value: Any?,
    debugLineStr: DwarfSection
): String? {
    return when (value) {
        is String -> value
        is Int -> crossSectionOperation {
            position(debugLineStr.contentOffset + value)
            readStringZ()
        }
        else -> null
    }
}
