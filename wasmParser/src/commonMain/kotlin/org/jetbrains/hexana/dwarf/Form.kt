package org.jetbrains.hexana.dwarf


enum class Form(val value: UShort) {
    DW_FORM_null(0u),
    DW_FORM_addr(0x01u),
    DW_FORM_block2(0x03u),
    DW_FORM_block4(0x4u),
    DW_FORM_data2(0x5u),
    DW_FORM_data4(0x06u),
    DW_FORM_data8(0x07u),
    DW_FORM_string(0x08u),
    DW_FORM_block(0x09u),
    DW_FORM_block1(0x0au),
    DW_FORM_data1(0x0bu),
    DW_FORM_flag(0x0cu),
    DW_FORM_sdata(0x0du),
    DW_FORM_strp(0x0eu),
    DW_FORM_udata(0x0fu),
    DW_FORM_ref_addr(0x10u),
    DW_FORM_ref1(0x11u),
    DW_FORM_ref2(0x12u),
    DW_FORM_ref4(0x13u),
    DW_FORM_ref8(0x14u),
    DW_FORM_ref_udata(0x15u),
    DW_FORM_indirect(0x16u),
    DW_FORM_sec_offset(0x17u),
    DW_FORM_exprloc(0x18u),
    DW_FORM_flag_present(0x19u),
    DW_FORM_ref_sig8(0x20u),
    // DWARF 5
    DW_FORM_implicit_const(0x21u),
    DW_FORM_strx1(0x25u),
    DW_FORM_strx2(0x26u),
    DW_FORM_strx4(0x27u),
    DW_FORM_strx8(0x28u)
}

fun findForm(v: UShort): Form = Form.entries.find{ it.value == v } ?: error("Unknown form: $v")
