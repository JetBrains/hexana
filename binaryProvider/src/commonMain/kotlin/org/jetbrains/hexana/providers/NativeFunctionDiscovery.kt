package org.jetbrains.hexana.providers

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.elf.ElfAddressMapper
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol
import org.jetbrains.hexana.providers.macho.MachOSection
import org.jetbrains.hexana.providers.macho.MachOSymbol
import org.jetbrains.hexana.providers.pe.PeCoffSymbol
import org.jetbrains.hexana.providers.pe.PeSection

data class NativeFunctionRecord(
    val name: String,
    val demangledName: String?,
    val symbolIndex: Int,
    val source: NativeFunctionSymbolSource,
    val binding: Int,
    val sectionIndex: Int,
    val sectionName: String?,
    val imported: Boolean,
    val thunkLike: Boolean,
    val virtualAddress: ULong,
    val fileOffset: Long?,
    val size: ULong?,
    val rangeConfidence: NativeFunctionRangeConfidence,
    val recordRange: NativeByteRange,
    /** True when the symbol's original st_value had bit 0 set, indicating a Thumb-mode function. */
    val isThumb: Boolean = false,
)

enum class NativeFunctionSymbolSource {
    Symtab,
    Dynsym,
    ExternalBackend,
    Heuristic,
    Unknown,
}

enum class NativeFunctionRangeConfidence {
    SymbolSize,
    NextSymbolInSection,
    BackendProvided,
    Unknown,
}

object NativeFunctionDiscovery {
    /**
     * Builds function records for a Mach-O binary from its symbol table.
     *
     * Mach-O's `nlist` doesn't carry an explicit "function" symbol type the
     * way ELF's STT_FUNC does, so we approximate: a symbol is treated as a
     * function when it's either undefined (an import — `n_type & N_TYPE` is
     * `N_UNDF`) or defined in a code-bearing section (`N_SECT` pointing at a
     * section whose name is `__text` or whose flags carry
     * `S_ATTR_PURE_INSTRUCTIONS` / `S_ATTR_SOME_INSTRUCTIONS`). Stab
     * debug entries (`n_type & N_STAB`) are filtered out.
     *
     * Section lookup uses the symbol's `n_sect` as a 1-based index into the
     * flattened section list — that's the on-disk convention preserved by
     * [MachOSection.index] within each segment's list, joined globally by
     * `provider.sections = segments.flatMap { it.sections }`.
     *
     * File-offset and size come from the symbol's [MachOSymbol.value] (a
     * virtual address) projected back through the section's
     * `(address, fileOffset, size)`. Sizes are inferred from the gap to the
     * next function symbol in the same section, capped by the section end —
     * Mach-O records don't carry an explicit symbol size.
     */
    fun fromMachO(
        symbols: List<MachOSymbol>,
        sections: List<MachOSection>,
    ): List<NativeFunctionRecord> {
        val sectionByGlobalIndex: Map<Int, MachOSection> = sections.withIndex()
            .associate { (i, sec) -> (i + 1) to sec }

        val candidateSymbols = symbols.filter { symbol ->
            if (symbol.name.isBlank()) return@filter false
            if ((symbol.type and N_STAB) != 0) return@filter false
            when (symbol.type and N_TYPE) {
                N_UNDF -> true
                N_SECT -> sectionByGlobalIndex[symbol.sectionIndex]?.isCodeBearing() == true
                else -> false
            }
        }

        // Group defined function symbols by section so size inference can find
        // the next symbol's value in the same section.
        val definedBySection: Map<Int, SortedAddresses> = candidateSymbols
            .filter { (it.type and N_TYPE) == N_SECT }
            .groupBy({ it.sectionIndex }, { it.value })
            .mapValues { SortedAddresses(it.value) }

        return candidateSymbols.map { symbol ->
            val isUndefined = (symbol.type and N_TYPE) == N_UNDF
            val section = if (isUndefined) null else sectionByGlobalIndex[symbol.sectionIndex]
            val resolvedSize = if (isUndefined) {
                ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
            } else {
                resolveMachOFunctionSize(symbol, section, definedBySection)
            }
            val fileOffset = if (isUndefined || section == null) null
            else machOFileOffsetOf(symbol.value, section)
            NativeFunctionRecord(
                name = symbol.name,
                demangledName = NativeSymbolDemangler.demangle(symbol.name),
                symbolIndex = symbol.index,
                source = NativeFunctionSymbolSource.Symtab,
                binding = if ((symbol.type and N_EXT) != 0) 1 else 0,
                sectionIndex = symbol.sectionIndex,
                sectionName = section?.qualifiedName(),
                imported = isUndefined,
                thunkLike = section?.name == "__stubs" || section?.name == "__stub_helper",
                virtualAddress = symbol.value,
                fileOffset = fileOffset,
                size = resolvedSize.size,
                rangeConfidence = resolvedSize.confidence,
                recordRange = symbol.recordRange,
            )
        }
    }

    fun fromElf(
        symbols: List<ElfSymbol>,
        sections: List<ElfSectionHeader>,
        programHeaders: List<ElfProgramHeader>,
        machine: Int? = null,
    ): List<NativeFunctionRecord> {
        val isArm = machine == 0x28
        val functionSymbols = symbols
            .filter { symbol -> symbol.type == STT_FUNC && symbol.name.isNotBlank() }
            .sortedWith(compareBy<ElfSymbol> { it.isUndefined }.thenBy { it.sectionIndex }.thenBy { it.value })
        val definedFunctionSymbolsBySection = functionSymbols
            .filterNot { it.isUndefined }
            .groupBy({ it.sectionIndex }, { if (isArm) it.value and 1uL.inv() else it.value })
            .mapValues { SortedAddresses(it.value) }

        return functionSymbols.map { symbol ->
            val source = symbol.symbolSource(sections)
            val section = sections.firstOrNull { it.index == symbol.sectionIndex }

            // For EM_ARM STT_FUNC symbols, bit 0 of st_value signals Thumb mode. Strip it for
            // address arithmetic so file-offset mapping and size gaps are computed correctly.
            val isThumb = isArm && !symbol.isUndefined && (symbol.value and 1uL) == 1uL
            val maskedValue = if (isThumb) symbol.value and 1uL.inv() else symbol.value

            val size = symbol.resolvedFunctionSize(definedFunctionSymbolsBySection, maskedValue)
            NativeFunctionRecord(
                name = symbol.name,
                demangledName = NativeSymbolDemangler.demangle(symbol.name),
                symbolIndex = symbol.index,
                source = source,
                binding = symbol.binding,
                sectionIndex = symbol.sectionIndex,
                sectionName = section?.name?.takeIf { it.isNotBlank() },
                imported = symbol.isUndefined,
                thunkLike = symbol.isThunkLike(section),
                virtualAddress = maskedValue,
                fileOffset = if (symbol.isUndefined) {
                    null
                } else if (section != null && section.address == 0uL) {
                    // Relocatable object (ET_REL — e.g. a `.o` member of a `.a` archive):
                    // sections are not laid out in memory (sh_addr == 0) and there are no
                    // program headers, so st_value is an offset from the start of the symbol's
                    // section, not a virtual address. Map it straight through the section's file
                    // offset. (Without this, ElfAddressMapper returns null and the function is
                    // dropped from the disassembly listing.)
                    (section.fileOffset + maskedValue)
                        .takeIf { it <= Long.MAX_VALUE.toULong() }
                        ?.toLong()
                } else {
                    ElfAddressMapper.virtualAddressToFileOffset(maskedValue, programHeaders, sections)
                        ?.takeIf { it <= Long.MAX_VALUE.toULong() }
                        ?.toLong()
                },
                size = size.size,
                rangeConfidence = size.confidence,
                recordRange = symbol.recordRange,
                isThumb = isThumb,
            )
        }
    }

    /**
     * Builds function records for a COFF object file (e.g. an `.obj`/`.o` member of a `.lib`
     * static library) from its symbol table.
     *
     * A symbol is treated as a function when its COFF type field carries the
     * `DT_FUNCTION` derived type (`ISFCN`: `(type and 0x30) == 0x20`). Defined functions have a
     * 1-based `sectionNumber` pointing at a section and a section-relative `value`; undefined
     * externals (`sectionNumber <= 0`, e.g. `memcpy`) are imports with no body.
     *
     * COFF carries no symbol size, so — like the ELF/Mach-O paths — a defined function's size is
     * the gap to the next defined function symbol in the same section, capped at the section's
     * raw-data size. `fileOffset = section.rawDataPointer + value`; `virtualAddress` is the
     * section RVA plus the offset (RVAs are section-relative in an unlinked object, so this is a
     * display value only).
     */
    fun fromCoff(
        symbols: List<PeCoffSymbol>,
        sections: List<PeSection>,
    ): List<NativeFunctionRecord> {
        val functionSymbols = symbols.filter { (it.type and COFF_DT_FUNCTION_MASK) == COFF_DT_FUNCTION &&
            it.name.isNotBlank() }
        val definedBySection: Map<Int, SortedAddresses> = functionSymbols
            .filter { it.sectionNumber > 0 }
            .groupBy({ it.sectionNumber }, { it.value.toULong() })
            .mapValues { SortedAddresses(it.value) }

        return functionSymbols.map { symbol ->
            val imported = symbol.sectionNumber <= 0
            // COFF sectionNumber is 1-based; parsed sections carry a 0-based `index`.
            val section = if (imported) null else sections.firstOrNull { it.index == symbol.sectionNumber - 1 }
            val resolvedSize = if (imported || section == null) {
                ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
            } else {
                resolveCoffFunctionSize(symbol, section, definedBySection[symbol.sectionNumber])
            }
            NativeFunctionRecord(
                name = symbol.name,
                demangledName = NativeSymbolDemangler.demangle(symbol.name),
                symbolIndex = symbol.index,
                source = NativeFunctionSymbolSource.Symtab,
                // IMAGE_SYM_CLASS_EXTERNAL (2) is global; STATIC (3) and the rest are local.
                binding = if (symbol.storageClass == COFF_SYM_CLASS_EXTERNAL) 1 else 0,
                sectionIndex = symbol.sectionNumber,
                sectionName = section?.name?.takeIf { it.isNotBlank() },
                imported = imported,
                thunkLike = false,
                virtualAddress = if (section != null) {
                    section.virtualAddress.toULong() + symbol.value.toULong()
                } else {
                    symbol.value.toULong()
                },
                fileOffset = if (imported || section == null) {
                    null
                } else {
                    section.rawDataPointer.toLong() + symbol.value.toLong()
                },
                size = resolvedSize.size,
                rangeConfidence = resolvedSize.confidence,
                recordRange = symbol.recordRange,
            )
        }
    }
}

private fun resolveCoffFunctionSize(
    symbol: PeCoffSymbol,
    section: PeSection,
    definedInSection: SortedAddresses?,
): ResolvedFunctionSize {
    // `value` is section-relative; size = distance to the next defined function symbol in the
    // same section, capped at the section's raw-data size (the section-relative end).
    val nextValue = definedInSection?.firstAfter(symbol.value.toULong())
    val sectionEnd = section.rawDataSize.toULong()
    val end = when {
        nextValue != null -> minOf(nextValue, sectionEnd)
        else -> sectionEnd
    }
    val size = (end.toLong() - symbol.value.toULong().toLong()).takeIf { it > 0L }
        ?: return ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    return ResolvedFunctionSize(size.toULong(), NativeFunctionRangeConfidence.NextSymbolInSection)
}

// COFF symbol type: `ISFCN` = derived type DT_FUNCTION (2) in bits 4-5 of the type word.
private const val COFF_DT_FUNCTION_MASK: Int = 0x30
private const val COFF_DT_FUNCTION: Int = 0x20
private const val COFF_SYM_CLASS_EXTERNAL: Int = 2

/**
 * The distinct start addresses of one section's defined functions, sorted, so the
 * "size = gap to the next symbol" rule is a binary search rather than a scan of every
 * symbol per symbol — quadratic, which took seconds on a binary with ~50k functions.
 */
private class SortedAddresses(values: Collection<ULong>) {
    private val sorted: List<ULong> = values.distinct().sorted()

    /** The smallest address strictly greater than [address], or `null`. */
    fun firstAfter(address: ULong): ULong? {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= address) lo = mid + 1 else hi = mid
        }
        return sorted.getOrNull(lo)
    }
}

private data class ResolvedFunctionSize(
    val size: ULong?,
    val confidence: NativeFunctionRangeConfidence,
)

private val ElfSymbol.isUndefined: Boolean
    get() = sectionIndex == SHN_UNDEF

private fun ElfSymbol.symbolSource(sections: List<ElfSectionHeader>): NativeFunctionSymbolSource =
    when (sections.firstOrNull { it.index == tableSectionIndex }?.name) {
        ".symtab" -> NativeFunctionSymbolSource.Symtab
        ".dynsym" -> NativeFunctionSymbolSource.Dynsym
        else -> NativeFunctionSymbolSource.Unknown
    }

private fun ElfSymbol.isThunkLike(section: ElfSectionHeader?): Boolean =
    name.contains("@plt") || section?.name == ".plt" || section?.name == ".plt.got" || section?.name == ".plt.sec"

private fun ElfSymbol.resolvedFunctionSize(
    definedFunctionSymbolsBySection: Map<Int, SortedAddresses>,
    maskedValue: ULong = value,
): ResolvedFunctionSize {
    if (size > 0uL) {
        return ResolvedFunctionSize(size, NativeFunctionRangeConfidence.SymbolSize)
    }

    if (isUndefined || sectionIndex <= 0) {
        return ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    }

    // On ARM the addresses are already masked, so bit-0 Thumb markers don't skew sizes.
    val nextValue = definedFunctionSymbolsBySection[sectionIndex]?.firstAfter(maskedValue)
    val nextSize = nextValue?.let { it - maskedValue }?.takeIf { it > 0uL }
    return if (nextSize != null) {
        ResolvedFunctionSize(nextSize, NativeFunctionRangeConfidence.NextSymbolInSection)
    } else {
        ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    }
}

private const val SHN_UNDEF: Int = 0
private const val STT_FUNC: Int = 2

// Mach-O `nlist::n_type` bit layout (see /usr/include/mach-o/nlist.h).
private const val N_STAB: Int = 0xE0
private const val N_TYPE: Int = 0x0E
private const val N_UNDF: Int = 0x0
private const val N_SECT: Int = 0xE
private const val N_EXT: Int = 0x01

// `MachOSection.flags`: S_ATTR_PURE_INSTRUCTIONS | S_ATTR_SOME_INSTRUCTIONS.
private const val MACHO_S_ATTR_CODE_MASK: UInt = 0x80000400u

private fun MachOSection.isCodeBearing(): Boolean =
    name == "__text" || (flags and MACHO_S_ATTR_CODE_MASK) != 0u

/** `"__TEXT,__text"`-style qualified name; empty parts are dropped. */
private fun MachOSection.qualifiedName(): String? {
    val seg = segmentName.takeIf { it.isNotBlank() }
    val sec = name.takeIf { it.isNotBlank() }
    return when {
        seg != null && sec != null -> "$seg,$sec"
        sec != null -> sec
        seg != null -> seg
        else -> null
    }
}

private fun machOFileOffsetOf(virtualAddress: ULong, section: MachOSection): Long? {
    if (virtualAddress < section.address) return null
    val withinSection = virtualAddress - section.address
    if (withinSection >= section.size) return null
    val fileOffset = section.fileOffset + withinSection
    return if (fileOffset > Long.MAX_VALUE.toULong()) null else fileOffset.toLong()
}

private fun resolveMachOFunctionSize(
    symbol: MachOSymbol,
    section: MachOSection?,
    definedBySection: Map<Int, SortedAddresses>,
): ResolvedFunctionSize {
    // `nlist` carries no n_size field. Convention: size = distance to the
    // next defined symbol in the same section, capped at the section end.
    val nextValue = definedBySection[symbol.sectionIndex]?.firstAfter(symbol.value)
    val sectionEnd = section?.let { it.address + it.size }
    val end = when {
        nextValue != null && sectionEnd != null -> minOf(nextValue, sectionEnd)
        nextValue != null -> nextValue
        sectionEnd != null -> sectionEnd
        else -> return ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    }
    val size = (end - symbol.value).takeIf { it > 0uL }
        ?: return ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    return ResolvedFunctionSize(size, NativeFunctionRangeConfidence.NextSymbolInSection)
}
