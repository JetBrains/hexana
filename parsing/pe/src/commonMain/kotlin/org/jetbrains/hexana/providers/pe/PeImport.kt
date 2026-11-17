package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeByteRange

data class PeImportLibrary(
    val index: Int,
    val name: String,
    val importLookupTableRva: UInt,
    val importAddressTableRva: UInt,
    val entries: List<PeImportEntry>,
    val descriptorRange: NativeByteRange,
    val nameRange: NativeByteRange?,
)

data class PeImportEntry(
    val index: Int,
    val name: String?,
    val hint: Int?,
    val ordinal: ULong?,
    val thunkRva: UInt,
    val thunkRange: NativeByteRange,
    val hintNameRange: NativeByteRange?,
)
