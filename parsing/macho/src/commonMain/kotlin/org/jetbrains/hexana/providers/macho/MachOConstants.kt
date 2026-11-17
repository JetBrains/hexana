package org.jetbrains.hexana.providers.macho

object MachOLoadCommandType {
    const val Segment: UInt = 0x1u
    const val Symtab: UInt = 0x2u
    const val Dysymtab: UInt = 0xBu
    const val LoadDylib: UInt = 0xCu
    const val IdDylib: UInt = 0xDu
    const val LoadWeakDylib: UInt = 0x80000018u
    const val Segment64: UInt = 0x19u
    const val Uuid: UInt = 0x1Bu
    const val ReexportDylib: UInt = 0x8000001Fu
    const val LazyLoadDylib: UInt = 0x20u
    const val LoadUpwardDylib: UInt = 0x80000023u

    val knownTypes: Set<UInt> = setOf(
        Segment,
        Symtab,
        Dysymtab,
        LoadDylib,
        IdDylib,
        LoadWeakDylib,
        Segment64,
        Uuid,
        ReexportDylib,
        LazyLoadDylib,
        LoadUpwardDylib,
    )
}
