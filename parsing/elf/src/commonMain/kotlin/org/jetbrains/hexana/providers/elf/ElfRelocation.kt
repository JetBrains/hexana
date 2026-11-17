package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class ElfRelocation(
    val index: Int,
    val relocationSectionIndex: Int,
    val targetSectionIndex: Int,
    val offset: ULong,
    val info: ULong,
    val symbolIndex: UInt,
    val type: UInt,
    val addend: Long?,
    val symbol: ElfSymbol?,
    val recordRange: NativeByteRange,
)
