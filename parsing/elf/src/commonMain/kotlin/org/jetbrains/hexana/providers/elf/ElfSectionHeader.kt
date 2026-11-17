package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class ElfSectionHeader(
    val index: Int,
    val nameOffset: UInt,
    val name: String,
    val type: UInt,
    val flags: ULong,
    val address: ULong,
    val fileOffset: ULong,
    val size: ULong,
    val link: UInt,
    val info: UInt,
    val addressAlignment: ULong,
    val entrySize: ULong,
    val headerRange: NativeByteRange,
    val contentRange: NativeByteRange?,
)
