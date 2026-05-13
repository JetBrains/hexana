package org.jetbrains.hexana.dwarf

open class DwarfEntry(val offset: Int, val size: Int)
open class DwarfSection(val contentOffset: Int, val contentSize: Int, val accessor: DwarfAccessor): DwarfEntry(contentOffset, contentSize)

interface DwarfAccessor {
    fun section(name: String): DwarfSection?

    fun debugLineSection(): DwarfLineSection?

    fun debugInfoSection(): DwarfInfoSection?

    fun debugAbbrevSection(): DwarfAbbrevSection?

    fun debugStrSection(): DwarfStringSection?

    fun debugAddrSection(): DwarfAddressSection?

    fun debugLocSection(): DwarfLocSection?
}
