package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeCoffSymbol(
    val index: Int,
    val name: String,
    val value: UInt,
    val sectionNumber: Int,
    val type: Int,
    val storageClass: Int,
    val auxiliaryCount: Int,
    val recordRange: NativeByteRange,
)
