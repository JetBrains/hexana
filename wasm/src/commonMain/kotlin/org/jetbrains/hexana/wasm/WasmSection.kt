package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.CommonByteBuffer

sealed class WasmSection(val size: Int, val offset: Int)

class WasmSectionCustom(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 0
    }
}

class WasmSectionTypes(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 1
    }
}

class WasmSectionImport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 2
    }
}

class WasmSectionFunctions(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 3
    }
}

class WasmSectionTable(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 4
    }
}

class WasmSectionMemory(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 5
    }
}

class WasmSectionGlobal(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 6
    }
}

class WasmSectionExport(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 7
    }
}

class WasmSectionStart(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 8
    }
}

class WasmSectionElement(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 9
    }
}

class WasmSectionCode(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 10
    }
}

class WasmSectionData(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 11
    }
}

class WasmSectionDataCount(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 12
    }
}

class WasmSectionException(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 13
    }
}

class WasmSectionUnresolved(val id: Byte, size: Int, offset: Int): WasmSection(size, offset)

fun CommonByteBuffer.scanSection(): WasmSectionUnresolved {
    val sectionId = get()
    val sectionSize = getULEB128().toInt()

    return WasmSectionUnresolved(sectionId, sectionSize, position())
}

fun CommonByteBuffer.resolveSection(): WasmSection? {
    val unresolvedSection = scanSection()
    return when (unresolvedSection.id) {
        WasmSectionCustom.SECTION_ID -> WasmSectionCustom(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionTypes.SECTION_ID -> WasmSectionTypes(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionImport.SECTION_ID -> WasmSectionImport(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionFunctions.SECTION_ID -> WasmSectionFunctions(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionTable.SECTION_ID -> WasmSectionTable(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionMemory.SECTION_ID -> WasmSectionMemory(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionGlobal.SECTION_ID -> WasmSectionGlobal(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionExport.SECTION_ID -> WasmSectionExport(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionStart.SECTION_ID -> WasmSectionStart(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionElement.SECTION_ID -> WasmSectionElement(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionCode.SECTION_ID -> WasmSectionCode(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionData.SECTION_ID -> WasmSectionData(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionDataCount.SECTION_ID -> WasmSectionDataCount(unresolvedSection.size, unresolvedSection.offset)
        WasmSectionException.SECTION_ID -> WasmSectionException(unresolvedSection.size, unresolvedSection.offset)
        else -> null
    }
}
