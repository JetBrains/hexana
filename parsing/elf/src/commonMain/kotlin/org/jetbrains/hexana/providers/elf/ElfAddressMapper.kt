package org.jetbrains.hexana.providers.elf

object ElfAddressMapper {
    fun virtualAddressToFileOffset(
        virtualAddress: ULong,
        programHeaders: List<ElfProgramHeader>,
        sections: List<ElfSectionHeader> = emptyList(),
    ): ULong? {
        programHeaders.firstNotNullOfOrNull {
            it.mapLoadableVirtualAddress(virtualAddress)
        }?.let { return it }

        return sections.firstNotNullOfOrNull {
            it.mapSectionVirtualAddress(virtualAddress)
        }
    }

    fun fileOffsetToVirtualAddress(
        fileOffset: ULong,
        programHeaders: List<ElfProgramHeader>,
        sections: List<ElfSectionHeader> = emptyList(),
    ): ULong? {
        programHeaders.firstNotNullOfOrNull {
            it.mapLoadableFileOffset(fileOffset)
        }?.let { return it }

        return sections.firstNotNullOfOrNull {
            it.mapSectionFileOffset(fileOffset)
        }
    }
}

private const val PT_LOAD = 1u

private fun ElfProgramHeader.mapLoadableVirtualAddress(virtualAddress: ULong): ULong? {
    if (type != PT_LOAD || fileSize == 0uL) return null
    if (virtualAddress < this.virtualAddress) return null

    val delta = virtualAddress - this.virtualAddress
    if (delta >= fileSize) return null

    return fileOffset + delta
}

private fun ElfProgramHeader.mapLoadableFileOffset(fileOffset: ULong): ULong? {
    if (type != PT_LOAD || fileSize == 0uL) return null
    if (fileOffset < this.fileOffset) return null

    val delta = fileOffset - this.fileOffset
    if (delta >= fileSize) return null

    return virtualAddress + delta
}

private fun ElfSectionHeader.mapSectionVirtualAddress(virtualAddress: ULong): ULong? {
    if (contentRange == null || size == 0uL || address == 0uL) return null
    if (virtualAddress < address) return null

    val delta = virtualAddress - address
    if (delta >= size) return null

    return fileOffset + delta
}

private fun ElfSectionHeader.mapSectionFileOffset(fileOffset: ULong): ULong? {
    if (contentRange == null || size == 0uL || address == 0uL) return null
    if (fileOffset < this.fileOffset) return null

    val delta = fileOffset - this.fileOffset
    if (delta >= size) return null

    return address + delta
}
