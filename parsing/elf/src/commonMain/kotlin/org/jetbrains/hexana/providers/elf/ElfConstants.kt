package org.jetbrains.hexana.providers.elf

object ElfSectionType {
    const val Null: UInt = 0u
    const val ProgBits: UInt = 1u
    const val SymbolTable: UInt = 2u
    const val StringTable: UInt = 3u
    const val Rela: UInt = 4u
    const val Hash: UInt = 5u
    const val Dynamic: UInt = 6u
    const val Note: UInt = 7u
    const val NoBits: UInt = 8u
    const val Rel: UInt = 9u
    const val Shlib: UInt = 10u
    const val DynamicSymbolTable: UInt = 11u
    const val InitArray: UInt = 14u
    const val FiniArray: UInt = 15u
    const val PreinitArray: UInt = 16u
    const val Group: UInt = 17u
    const val SymtabSectionHeaderIndex: UInt = 18u

    val knownTypes: Set<UInt> = setOf(
        Null,
        ProgBits,
        SymbolTable,
        StringTable,
        Rela,
        Hash,
        Dynamic,
        Note,
        NoBits,
        Rel,
        Shlib,
        DynamicSymbolTable,
        InitArray,
        FiniArray,
        PreinitArray,
        Group,
        SymtabSectionHeaderIndex,
    )
}

object ElfProgramHeaderType {
    const val Null: UInt = 0u
    const val Load: UInt = 1u
    const val Dynamic: UInt = 2u
    const val Interpreter: UInt = 3u
    const val Note: UInt = 4u
    const val Shlib: UInt = 5u
    const val ProgramHeader: UInt = 6u
    const val Tls: UInt = 7u
    const val GnuEhFrame: UInt = 0x6474E550u
    const val GnuStack: UInt = 0x6474E551u
    const val GnuRelro: UInt = 0x6474E552u

    val knownTypes: Set<UInt> = setOf(
        Null,
        Load,
        Dynamic,
        Interpreter,
        Note,
        Shlib,
        ProgramHeader,
        Tls,
        GnuEhFrame,
        GnuStack,
        GnuRelro,
    )
}
