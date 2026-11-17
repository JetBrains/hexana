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
        get().toUByte()
    }

    fun CommonByteBuffer.addr2(off: Int): UShort = crossSectionOperation {
        position(contentOffset + off)
        getShortLE().toUShort()
    }

    fun CommonByteBuffer.addr4(off: Int): UInt = crossSectionOperation {
        position(contentOffset + off)
        getIntLE().toUInt()
    }

    fun CommonByteBuffer.addrx(off: Int, addressSize:Int): ULong = crossSectionOperation {
        position(contentOffset + off)
        when (addressSize) {
            4 -> getIntLE().toUInt().toULong()
            8 -> getLongLE().toULong()
            else -> throw IllegalArgumentException("Unsupported address size: $addressSize")
        }
    }

}