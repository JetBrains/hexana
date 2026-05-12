package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeSection(
    val index: Int,
    val name: String,
    val virtualSize: UInt,
    val virtualAddress: UInt,
    val rawDataSize: UInt,
    val rawDataPointer: UInt,
    val relocationPointer: UInt,
    val lineNumberPointer: UInt,
    val relocationCount: Int,
    val lineNumberCount: Int,
    val characteristics: UInt,
    val headerRange: NativeByteRange,
    val contentRange: NativeByteRange?,
)
