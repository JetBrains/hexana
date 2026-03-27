package org.jetbrains.hexana.dwarf

open class DwarfEntry(val offset: Int, val size: Int)
open class DwarfSection(val contentOffset: Int, val contentSize: Int): DwarfEntry(contentOffset, contentSize)
