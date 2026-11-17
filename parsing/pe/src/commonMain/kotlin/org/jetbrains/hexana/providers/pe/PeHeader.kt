package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeDosHeader(
    val peHeaderOffset: UInt,
    val mzRange: NativeByteRange,
    val peHeaderOffsetRange: NativeByteRange,
    val peSignatureRange: NativeByteRange,
)

data class PeCoffHeader(
    val machine: Int,
    val sectionCount: Int,
    val timeDateStamp: UInt,
    val symbolTableOffset: UInt,
    val symbolCount: UInt,
    val optionalHeaderSize: Int,
    val characteristics: Int,
    val headerRange: NativeByteRange,
)

data class PeOptionalHeader(
    val wordSize: NativeBinaryWordSize,
    val magic: Int,
    val addressOfEntryPoint: UInt,
    val imageBase: ULong,
    val sectionAlignment: UInt,
    val fileAlignment: UInt,
    val sizeOfImage: UInt,
    val sizeOfHeaders: UInt,
    val subsystem: Int,
    val dllCharacteristics: Int,
    val dataDirectoryCount: UInt,
    val dataDirectories: List<PeDataDirectory>,
    val headerRange: NativeByteRange,
)

data class PeDataDirectory(
    val index: Int,
    val name: String,
    val rva: UInt,
    val size: UInt,
    val recordRange: NativeByteRange,
)
