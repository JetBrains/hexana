package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeExportDirectory(
    val name: String,
    val ordinalBase: UInt,
    val functionCount: UInt,
    val nameCount: UInt,
    val directoryRange: NativeByteRange,
    val nameRange: NativeByteRange?,
    val entries: List<PeExportEntry>,
)

data class PeExportEntry(
    val index: Int,
    val name: String?,
    val ordinal: UInt,
    val rva: UInt,
    val forwarder: String?,
    val addressTableRange: NativeByteRange,
    val nameRange: NativeByteRange?,
)
