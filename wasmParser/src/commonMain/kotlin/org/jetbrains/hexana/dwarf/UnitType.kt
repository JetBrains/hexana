package org.jetbrains.hexana.dwarf

sealed class UnitType(val value: UByte) {
    object DW_UT_compile: UnitType(0x01u)
    object DW_UT_type: UnitType(0x02u)
    object DW_UT_partial: UnitType(0x03u)
    object DW_UT_skeleton: UnitType(0x04u)
    object DW_UT_split_compile: UnitType(0x05u)
    object DW_UT_split_type: UnitType(0x06u)
    object DW_UT_lo_user: UnitType(0x80u)
    object DW_UT_hi_user: UnitType(0xffu)
    class DW_UT_user_defined(value: UByte) : UnitType(value)
}

fun UByte.toUnitType() = when(this) {
    0x01.toUByte() -> UnitType.DW_UT_compile
    0x02.toUByte() -> UnitType.DW_UT_type
    0x03.toUByte() -> UnitType.DW_UT_partial
    0x04.toUByte() -> UnitType.DW_UT_skeleton
    0x05.toUByte() -> UnitType.DW_UT_split_compile
    0x06.toUByte() -> UnitType.DW_UT_split_type
    in 0x80u..0xffu -> UnitType.DW_UT_user_defined(this)
    else -> throw IllegalArgumentException("Unknown unit type: $this")
}
