package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeUnresolvedRecord
import org.jetbrains.hexana.parsing.common.NativeByteRange

sealed interface PeFile {
    val unresolvedRecords: List<NativeUnresolvedRecord>
    val diagnostics: List<NativeParserDiagnostic>
}

data class PeImageFile(
    val dosHeader: PeDosHeader,
    val coffHeader: PeCoffHeader,
    val optionalHeader: PeOptionalHeader,
    val sections: List<PeSection>,
    val imports: List<PeImportLibrary>,
    val exports: PeExportDirectory?,
    val baseRelocations: List<PeBaseRelocation>,
    val debugEntries: List<PeDebugEntry>,
    val coffSymbols: List<PeCoffSymbol>,
    override val unresolvedRecords: List<NativeUnresolvedRecord>,
    override val diagnostics: List<NativeParserDiagnostic>,
) : PeFile

data class CoffObjectFile(
    val coffHeader: PeCoffHeader,
    val sections: List<PeSection>,
    val relocations: List<PeCoffRelocation>,
    val coffSymbols: List<PeCoffSymbol>,
    override val unresolvedRecords: List<NativeUnresolvedRecord>,
    override val diagnostics: List<NativeParserDiagnostic>,
) : PeFile

data class PeArchiveFile(
    val magicRange: NativeByteRange,
    val members: List<PeArchiveMember>,
    override val unresolvedRecords: List<NativeUnresolvedRecord>,
    override val diagnostics: List<NativeParserDiagnostic>,
) : PeFile

data class PeArchiveMember(
    val index: Int,
    val name: String,
    val timestamp: String,
    val ownerId: String,
    val groupId: String,
    val mode: String,
    val size: UInt,
    val headerRange: NativeByteRange,
    val contentRange: NativeByteRange,
    /**
     * True for the archive's own bookkeeping members (the symbol table `/`, the
     * GNU long-names table `//`, and the BSD symbol tables `__.SYMDEF` /
     * `__.SYMDEF SORTED`). These are not real object files and should be hidden
     * from a member listing. Resolved from the member name; header-only, no
     * content read.
     */
    val isBookkeeping: Boolean = false,
)
