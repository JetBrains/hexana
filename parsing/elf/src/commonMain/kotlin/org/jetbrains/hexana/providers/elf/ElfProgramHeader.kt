package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class ElfProgramHeader(
    val index: Int,
    val type: UInt,
    val flags: UInt,
    val fileOffset: ULong,
    val virtualAddress: ULong,
    val physicalAddress: ULong,
    val fileSize: ULong,
    val memorySize: ULong,
    val alignment: ULong,
    val headerRange: NativeByteRange,
    val fileRange: NativeByteRange?,
)
