package org.jetbrains.hexana.providers.macho

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class MachOLoadCommand(
    val index: Int,
    val type: UInt,
    val size: UInt,
    val range: NativeByteRange,
)

data class MachOUuid(
    val value: String,
    val commandRange: NativeByteRange,
    val uuidRange: NativeByteRange,
)

data class MachOSymtabCommand(
    val symbolTableOffset: UInt,
    val symbolCount: UInt,
    val stringTableOffset: UInt,
    val stringTableSize: UInt,
    val commandRange: NativeByteRange,
    val symbolTableRange: NativeByteRange?,
    val stringTableRange: NativeByteRange?,
)

data class MachODysymtabCommand(
    val localSymbolIndex: UInt,
    val localSymbolCount: UInt,
    val externalDefinedSymbolIndex: UInt,
    val externalDefinedSymbolCount: UInt,
    val undefinedSymbolIndex: UInt,
    val undefinedSymbolCount: UInt,
    val tableOfContentsOffset: UInt,
    val tableOfContentsCount: UInt,
    val moduleTableOffset: UInt,
    val moduleTableCount: UInt,
    val externalReferenceSymbolTableOffset: UInt,
    val externalReferenceSymbolTableCount: UInt,
    val indirectSymbolTableOffset: UInt,
    val indirectSymbolTableCount: UInt,
    val externalRelocationOffset: UInt,
    val externalRelocationCount: UInt,
    val localRelocationOffset: UInt,
    val localRelocationCount: UInt,
    val commandRange: NativeByteRange,
)

data class MachOSymbol(
    val index: Int,
    val nameOffset: UInt,
    val name: String,
    val type: Int,
    val sectionIndex: Int,
    val description: Int,
    val value: ULong,
    val recordRange: NativeByteRange,
)

data class MachODylibCommand(
    val index: Int,
    val type: UInt,
    val kind: MachODylibKind,
    val name: String,
    val timestamp: UInt,
    val currentVersion: UInt,
    val compatibilityVersion: UInt,
    val commandRange: NativeByteRange,
    val nameRange: NativeByteRange?,
)

enum class MachODylibKind {
    Load,
    WeakLoad,
    Reexport,
    UpwardLoad,
    LazyLoad,
    Id,
    Other,
}

data class MachORelocation(
    val index: Int,
    val sectionIndex: Int,
    val sectionName: String,
    val address: UInt,
    val symbolOrSectionIndex: UInt,
    val pcrel: Boolean,
    val length: Int,
    val external: Boolean,
    val type: Int,
    val scattered: Boolean,
    val value: UInt?,
    val symbol: MachOSymbol?,
    val recordRange: NativeByteRange,
)

data class MachOFatArch(
    val index: Int,
    val cpuType: Int,
    val cpuSubtype: Int,
    val offset: ULong,
    val size: ULong,
    val alignment: UInt,
    val reserved: UInt?,
    val archRange: NativeByteRange,
    val sliceRange: NativeByteRange?,
)

data class MachOSegment(
    val index: Int,
    val name: String,
    val virtualAddress: ULong,
    val virtualSize: ULong,
    val fileOffset: ULong,
    val fileSize: ULong,
    val maxProtection: UInt,
    val initialProtection: UInt,
    val sectionCount: UInt,
    val flags: UInt,
    val commandRange: NativeByteRange,
    val fileRange: NativeByteRange?,
    val sections: List<MachOSection>,
)

data class MachOSection(
    val index: Int,
    val segmentIndex: Int,
    val name: String,
    val segmentName: String,
    val address: ULong,
    val size: ULong,
    val fileOffset: ULong,
    val alignment: UInt,
    val relocationOffset: UInt,
    val relocationCount: UInt,
    val flags: UInt,
    val headerRange: NativeByteRange,
    val contentRange: NativeByteRange?,
)
