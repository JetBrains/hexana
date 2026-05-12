package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeDebugEntry(
    val index: Int,
    val type: UInt,
    val timeDateStamp: UInt,
    val sizeOfData: UInt,
    val addressOfRawData: UInt,
    val pointerToRawData: UInt,
    val codeView: PeCodeViewDebugInfo?,
    val recordRange: NativeByteRange,
    val dataRange: NativeByteRange?,
)

data class PeCodeViewDebugInfo(
    val signature: String,
    val guid: String?,
    val age: UInt?,
    val pdbPath: String?,
)
