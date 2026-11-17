package org.jetbrains.hexana.providers.macho

import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeUnresolvedRecord

sealed interface MachOFile {
    val unresolvedRecords: List<NativeUnresolvedRecord>
    val diagnostics: List<NativeParserDiagnostic>
}

data class MachOFatFile(
    val header: MachOFatHeader,
    val architectures: List<MachOFatArch>,
    override val unresolvedRecords: List<NativeUnresolvedRecord>,
    override val diagnostics: List<NativeParserDiagnostic>,
) : MachOFile

data class MachOThinFile(
    val header: MachOHeader,
    val loadCommands: List<MachOLoadCommand>,
    val segments: List<MachOSegment>,
    val uuid: MachOUuid?,
    val symtab: MachOSymtabCommand?,
    val dysymtab: MachODysymtabCommand?,
    val symbols: List<MachOSymbol>,
    val dylibs: List<MachODylibCommand>,
    val relocations: List<MachORelocation>,
    override val unresolvedRecords: List<NativeUnresolvedRecord>,
    override val diagnostics: List<NativeParserDiagnostic>,
) : MachOFile
