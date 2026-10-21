package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.parsing.common.NativeByteRange

/**
 * Portable PDB debug metadata (`dotnet/runtime` `docs/design/specs/PortablePdb-Metadata.md`):
 * an ECMA-335 metadata blob with a `#Pdb` stream and the debug tables 0x30..0x37. It is either a
 * standalone `.pdb` file or deflate-compressed inside the assembly (debug directory type 17).
 */
data class ClrPortablePdb(
    val header: ClrPdbStreamHeader,
    val documents: List<ClrPdbDocument>,
    /** Rows of MethodDebugInformation, one per MethodDef of the described assembly. */
    val methodDebugInformationCount: Int,
)

/** The `#Pdb` stream: PDB id, entry point and the row counts of the assembly's own tables. */
data class ClrPdbStreamHeader(
    /** First 16 bytes of the PDB id; equals the GUID in the assembly's CodeView record. */
    val guid: String,
    /** Last 4 bytes of the PDB id; equals the CodeView debug directory entry's timestamp. */
    val stamp: UInt,
    /** MethodDef token of the entry point, or 0. */
    val entryPointToken: UInt,
    /** Row counts of the type-system tables the debug tables index into, by table id. */
    val referencedTableRows: Map<Int, Int>,
    val range: NativeByteRange,
)

data class ClrPdbDocument(
    /** 1-based Document row. */
    val row: Int,
    val name: String,
    val hashAlgorithm: String?,
    val hash: String,
    val language: String?,
    val recordRange: NativeByteRange,
)

/** Well-known GUIDs of the Document table, in [guidLeString] spelling. */
object ClrPdbGuids {
    private val hashAlgorithms = mapOf(
        "FF1816EC-AA5E-4D10-87F7-6F4963833460" to "SHA-1",
        "8829D00F-11B8-4213-878B-770E8597AC16" to "SHA-256",
    )
    private val languages = mapOf(
        "3F5162F8-07C6-11D3-9053-00C04FA302A1" to "C#",
        "3A12D0B8-C26C-11D0-B442-00A0244A1DD2" to "Visual Basic",
        "AB4F38C9-B6E6-43BA-BE3B-58080B2CCCE3" to "F#",
    )

    fun hashAlgorithmName(guid: String): String = hashAlgorithms[guid] ?: guid
    fun languageName(guid: String): String = languages[guid] ?: guid
}
