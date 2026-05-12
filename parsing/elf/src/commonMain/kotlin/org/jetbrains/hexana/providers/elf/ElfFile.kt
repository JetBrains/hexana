package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeUnresolvedRecord

data class ElfFile(
    val header: ElfHeader,
    val sections: List<ElfSectionHeader>,
    val programHeaders: List<ElfProgramHeader>,
    val symbols: List<ElfSymbol>,
    val relocations: List<ElfRelocation>,
    val dynamicEntries: List<ElfDynamicEntry>,
    val unresolvedRecords: List<NativeUnresolvedRecord>,
    val diagnostics: List<NativeParserDiagnostic>,
)
