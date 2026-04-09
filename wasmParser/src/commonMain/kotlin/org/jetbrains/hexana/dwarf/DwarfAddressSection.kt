package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

class DwarfAddressSection(contentOffset: Int, contentSize: Int, accessor: DwarfAccessor) :
    DwarfSection(
        contentOffset,
        contentSize,
        accessor
    ) {

    fun CommonByteBuffer.addr1(off: Int): UByte = crossSectionOperation {
        position(contentOffset + off)
        readUByte()
    }

    fun CommonByteBuffer.addr2(off: Int): UShort = crossSectionOperation {
        position(contentOffset + off)
        readUShort()
    }

    fun CommonByteBuffer.addr4(off: Int): UInt = crossSectionOperation {
        position(contentOffset + off)
        readUInt()
    }

    fun CommonByteBuffer.addrx(off: Int, addressSize:Int): ULong = crossSectionOperation {
        position(contentOffset + off)
        when (addressSize) {
            4 -> readUInt().toULong()
            8 -> readULong()
            else -> throw IllegalArgumentException("Unsupported address size: $addressSize")
        }
    }

}