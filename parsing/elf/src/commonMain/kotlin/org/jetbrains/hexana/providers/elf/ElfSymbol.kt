package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class ElfSymbol(
    val index: Int,
    val tableSectionIndex: Int,
    val nameOffset: UInt,
    val name: String,
    val info: Int,
    val binding: Int,
    val type: Int,
    val other: Int,
    val visibility: Int,
    val sectionIndex: Int,
    val value: ULong,
    val size: ULong,
    val recordRange: NativeByteRange,
)
