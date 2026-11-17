package org.jetbrains.hexana.providers.macho

import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange

data class MachOHeader(
    val wordSize: NativeBinaryWordSize,
    val endian: NativeBinaryEndian,
    val magic: UInt,
    val cpuType: Int,
    val cpuSubtype: Int,
    val fileType: UInt,
    val loadCommandCount: UInt,
    val loadCommandsSize: UInt,
    val flags: UInt,
    val reserved: UInt?,
    val headerRange: NativeByteRange,
    val loadCommandsRange: NativeByteRange,
)

data class MachOFatHeader(
    val wordSize: NativeBinaryWordSize,
    val endian: NativeBinaryEndian,
    val archCount: UInt,
    val headerRange: NativeByteRange,
    val archTableRange: NativeByteRange,
)
