package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class ElfDynamicEntry(
    val index: Int,
    val dynamicSectionIndex: Int,
    val tag: Long,
    val value: ULong,
    val neededLibrary: String?,
    val recordRange: NativeByteRange,
)
