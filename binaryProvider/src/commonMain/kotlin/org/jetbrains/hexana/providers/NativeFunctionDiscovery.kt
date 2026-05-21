package org.jetbrains.hexana.providers

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.elf.ElfAddressMapper
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol

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
    fun fromElf(
        symbols: List<ElfSymbol>,
        sections: List<ElfSectionHeader>,
        programHeaders: List<ElfProgramHeader>,
    ): List<NativeFunctionRecord> {
        val functionSymbols = symbols
            .filter { symbol -> symbol.type == STT_FUNC && symbol.name.isNotBlank() }
            .sortedWith(compareBy<ElfSymbol> { it.isUndefined }.thenBy { it.sectionIndex }.thenBy { it.value })
        val definedFunctionSymbolsBySection = functionSymbols
            .filterNot { it.isUndefined }
            .groupBy { it.sectionIndex }

        return functionSymbols.map { symbol ->
            val source = symbol.symbolSource(sections)
            val section = sections.firstOrNull { it.index == symbol.sectionIndex }
            val size = symbol.resolvedFunctionSize(definedFunctionSymbolsBySection)
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
                virtualAddress = symbol.value,
                fileOffset = if (symbol.isUndefined) {
                    null
                } else {
                    ElfAddressMapper.virtualAddressToFileOffset(symbol.value, programHeaders, sections)
                        ?.takeIf { it <= Long.MAX_VALUE.toULong() }
                        ?.toLong()
                },
                size = size.size,
                rangeConfidence = size.confidence,
                recordRange = symbol.recordRange,
            )
        }
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
    definedFunctionSymbolsBySection: Map<Int, List<ElfSymbol>>,
): ResolvedFunctionSize {
    if (size > 0uL) {
        return ResolvedFunctionSize(size, NativeFunctionRangeConfidence.SymbolSize)
    }

    if (isUndefined) {
        return ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    }

    val nextSymbol = definedFunctionSymbolsBySection[sectionIndex]
        ?.firstOrNull { other -> other.value > value }
    val nextSize = nextSymbol?.value?.minus(value)?.takeIf { it > 0uL }
    return if (nextSize != null) {
        ResolvedFunctionSize(nextSize, NativeFunctionRangeConfidence.NextSymbolInSection)
    } else {
        ResolvedFunctionSize(null, NativeFunctionRangeConfidence.Unknown)
    }
}

private const val SHN_UNDEF: Int = 0
private const val STT_FUNC: Int = 2
