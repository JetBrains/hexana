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
    val majorVersion: Int = 0,
    val minorVersion: Int = 0,
) {
    /**
     * A CodeView record that names a Portable PDB rather than a Windows PDB: Roslyn writes minor
     * version `0x504D` ("PM"), and the entry's [timeDateStamp] is then the last 4 bytes of the PDB id.
     */
    val isPortablePdbCodeView: Boolean get() = type == PeDebugType.CODEVIEW && minorVersion == PORTABLE_PDB_MINOR_VERSION
}

/** `IMAGE_DEBUG_TYPE_*` values of the debug directory entries Hexana interprets. */
object PeDebugType {
    const val CODEVIEW: UInt = 2u
    const val REPRO: UInt = 16u
    const val EMBEDDED_PORTABLE_PDB: UInt = 17u
    const val PDB_CHECKSUM: UInt = 19u
}

private const val PORTABLE_PDB_MINOR_VERSION = 0x504D

data class PeCodeViewDebugInfo(
    val signature: String,
    val guid: String?,
    val age: UInt?,
    val pdbPath: String?,
)
