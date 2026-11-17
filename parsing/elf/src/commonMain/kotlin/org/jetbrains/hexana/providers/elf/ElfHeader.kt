package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange

data class ElfHeader(
    val wordSize: NativeBinaryWordSize,
    val endian: NativeBinaryEndian,
    val osAbi: Int,
    val abiVersion: Int,
    val fileType: Int,
    val machine: Int,
    val version: UInt,
    val entryPoint: ULong,
    val programHeaderOffset: ULong,
    val sectionHeaderOffset: ULong,
    val flags: UInt,
    val headerSize: Int,
    val programHeaderEntrySize: Int,
    val programHeaderCount: Int,
    val sectionHeaderEntrySize: Int,
    val sectionHeaderCount: Int,
    val sectionNameStringTableIndex: Int,
    val identRange: NativeByteRange,
    val headerRange: NativeByteRange,
    val fieldRanges: ElfHeaderFieldRanges,
) {
    val isElf64: Boolean
        get() = wordSize == NativeBinaryWordSize.Bits64
}

data class ElfHeaderFieldRanges(
    val ident: NativeByteRange,
    val osAbi: NativeByteRange,
    val abiVersion: NativeByteRange,
    val fileType: NativeByteRange,
    val machine: NativeByteRange,
    val version: NativeByteRange,
    val entryPoint: NativeByteRange,
    val programHeaderOffset: NativeByteRange,
    val sectionHeaderOffset: NativeByteRange,
    val flags: NativeByteRange,
    val headerSize: NativeByteRange,
    val programHeaderEntrySize: NativeByteRange,
    val programHeaderCount: NativeByteRange,
    val sectionHeaderEntrySize: NativeByteRange,
    val sectionHeaderCount: NativeByteRange,
    val sectionNameStringTableIndex: NativeByteRange,
)
