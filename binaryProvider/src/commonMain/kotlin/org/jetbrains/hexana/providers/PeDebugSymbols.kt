package org.jetbrains.hexana.providers

import org.jetbrains.hexana.pdb.PdbFile
import org.jetbrains.hexana.providers.pe.PeDebugEntry
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.providers.pe.clr.ClrPortablePdb

/** The PDB a PE image's CodeView debug record names, and how a PDB proves it is that one. */
sealed interface PePdbReference {
    val guid: String
    /** The path the linker recorded, often an absolute path on the build machine. */
    val path: String?

    /** Windows PDB: the GUID and the age, which a debugger compares with the PDB's DBI age. */
    data class Windows(override val guid: String, val age: UInt, override val path: String?) : PePdbReference

    /** Portable PDB: the GUID and the entry's timestamp, which together are the PDB id. */
    data class Portable(override val guid: String, val stamp: UInt, override val path: String?) : PePdbReference

    /** `GUID, age N` / `GUID, stamp 0x…`, as debug tabs show it. */
    val label: String
        get() = when (this) {
            is Windows -> "$guid, age $age"
            is Portable -> "$guid, stamp 0x${stamp.toString(16).uppercase()}"
        }

    companion object {
        /** The reference in [entries], or null when the image has no `RSDS` CodeView record. */
        fun of(entries: List<PeDebugEntry>): PePdbReference? {
            for (entry in entries) {
                val codeView = entry.codeView?.takeIf { it.signature == "RSDS" } ?: continue
                val guid = codeView.guid ?: continue
                val path = codeView.pdbPath?.takeIf { it.isNotBlank() }
                return if (entry.isPortablePdbCodeView) {
                    Portable(guid, entry.timeDateStamp, path)
                } else {
                    Windows(guid, codeView.age ?: 0u, path)
                }
            }
            return null
        }
    }
}

/** Why a candidate PDB was or was not accepted for an image. */
enum class PdbMatch(val description: String) {
    Matches("matches the image"),
    NoReference("the image has no CodeView record naming a PDB"),
    WrongKind("the image expects the other kind of PDB"),
    Unreadable("the file could not be read as a PDB"),
    TooLarge("the file is too large to load automatically"),
    GuidMismatch("the PDB GUID differs from the image's"),
    AgeMismatch("the PDB age differs from the image's (rebuilt since)"),
    StampMismatch("the PDB id stamp differs from the image's (rebuilt since)"),
}

/** Checks [symbols] against [reference] the way debuggers do: GUID plus age (Windows) or stamp (Portable). */
fun pdbMatch(reference: PePdbReference?, symbols: PdbSymbolsBinaryProvider): PdbMatch {
    if (reference == null) return PdbMatch.NoReference
    return when (symbols) {
        is PdbBinaryProvider -> {
            if (reference !is PePdbReference.Windows) return PdbMatch.WrongKind
            val identity = symbols.parsedPdb?.identity ?: return PdbMatch.Unreadable
            when {
                !identity.guid.equals(reference.guid, ignoreCase = true) -> PdbMatch.GuidMismatch
                identity.age != reference.age -> PdbMatch.AgeMismatch
                else -> PdbMatch.Matches
            }
        }
        is PortablePdbBinaryProvider -> {
            if (reference !is PePdbReference.Portable) return PdbMatch.WrongKind
            val header = symbols.metadata.portablePdb?.header ?: return PdbMatch.Unreadable
            when {
                !header.guid.equals(reference.guid, ignoreCase = true) -> PdbMatch.GuidMismatch
                header.stamp != reference.stamp -> PdbMatch.StampMismatch
                else -> PdbMatch.Matches
            }
        }
    }
}

/** A `.pdb` that was offered for an image and did not match it. */
data class PeDebugSymbolsRejection(val path: String?, val reason: PdbMatch)

/** Debug symbols in use for a PE image. */
sealed interface PeDebugSymbols {
    /** A matching `.pdb` file loaded from [path]. */
    data class File(val symbols: PdbSymbolsBinaryProvider, val path: String?) : PeDebugSymbols

    /** The Portable PDB embedded in the image's debug directory. */
    data class Embedded(val pdb: ClrPortablePdb) : PeDebugSymbols
}

/**
 * Native line info from a Windows PDB: C13 rows are `section:offset`, placed at
 * `image base + section RVA + offset` so they line up with disassembly addresses. Each row is
 * bounded by its block's code range, so padding between functions maps to no line.
 */
internal fun pdbNativeSourceLocations(pdb: PdbFile, imageBase: ULong, sections: List<PeSection>): NativeSourceLocationProvider? {
    val rows = pdb.lineBlocks.flatMap { block ->
        val section = sections.getOrNull(block.section - 1) ?: return@flatMap emptyList()
        val base = imageBase + section.virtualAddress.toULong()
        val end = base + block.offset.toULong() + block.codeSize.toULong()
        block.rows.map { row -> AddressLineRow(base + row.offset.toULong(), row.file, row.line, row.column, end) }
    }
    return rows.takeIf { it.isNotEmpty() }?.let(::AddressLineTable)
}
