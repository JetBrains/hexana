package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeBaseRelocation(
    val blockIndex: Int,
    val entryIndex: Int,
    val type: Int,
    val pageRva: UInt,
    val targetRva: UInt,
    val recordRange: NativeByteRange,
)

data class PeCoffRelocation(
    val index: Int,
    val sectionIndex: Int,
    val sectionName: String,
    val virtualAddress: UInt,
    val symbolTableIndex: UInt,
    val type: Int,
    val symbol: PeCoffSymbol?,
    val recordRange: NativeByteRange,
)
