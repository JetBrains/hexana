package org.jetbrains.hexana.dwarf


sealed class Form(val value: UShort) {
    object DW_FORM_null: Form(0u)
    object DW_FORM_addr: Form(0x01u)
    object DW_FORM_block2: Form(0x03u)
    object DW_FORM_block4: Form(0x4u)
    object DW_FORM_data2: Form(0x5u)
    object DW_FORM_data4: Form(0x06u)
    object DW_FORM_data8: Form(0x07u)
    object DW_FORM_string: Form(0x08u)
    object DW_FORM_block: Form(0x09u)
    object DW_FORM_block1: Form(0x0au)
    object DW_FORM_data1: Form(0x0bu)
    object DW_FORM_flag: Form(0x0cu)
    object DW_FORM_sdata: Form(0x0du)
    object DW_FORM_strp: Form(0x0eu)
    object DW_FORM_udata: Form(0x0fu)
    object DW_FORM_ref_addr: Form(0x10u)
    object DW_FORM_ref1: Form(0x11u)
    object DW_FORM_ref2: Form(0x12u)
    object DW_FORM_ref4: Form(0x13u)
    object DW_FORM_ref8: Form(0x14u)
    object DW_FORM_ref_udata: Form(0x15u)
    object DW_FORM_indirect: Form(0x16u)
    object DW_FORM_sec_offset: Form(0x17u)
    object DW_FORM_exprloc: Form(0x18u)
    object DW_FORM_flag_present: Form(0x19u)
    // DWARF 5
    object DW_FORM_strx: Form(0x1au)
    object DW_FORM_addrx: Form(0x1bu)
    object DW_FORM_ref_sup4: Form(0x1cu)
    object DW_FORM_strp_sup: Form(0x1du)
    object DW_FORM_data16: Form(0x1eu)
    object DW_FORM_line_strp: Form(0x1fu)
    object DW_FORM_ref_sig8: Form(0x20u)
    object DW_FORM_implicit_const: Form(0x21u)
    object DW_FORM_loclistx: Form(0x22u)
    object DW_FORM_rnglistx: Form(0x23u)
    object DW_FORM_refsup8: Form(0x24u)
    object DW_FORM_strx1: Form(0x25u)
    object DW_FORM_strx2: Form(0x26u)
    object DW_FORM_strx4: Form(0x27u)
    object DW_FORM_strx8: Form(0x28u)
    object DW_FORM_addrx1: Form(0x29u)
    object DW_FORM_addrx2: Form(0x2au)
    object DW_FORM_addrx4: Form(0x2bu)
    object DW_FORM_addrx8: Form(0x2cu)
    class DW_FORM_Reserved(v: UShort): Form(v)
    class DW_FORM_Unknown(v: UShort): Form(v)
}

fun findForm(v: UShort): Form? = when(v) {
    Form.DW_FORM_null.value -> Form.DW_FORM_null
    Form.DW_FORM_addr.value -> Form.DW_FORM_addr
    0x02.toUShort() -> Form.DW_FORM_Reserved(v)
    Form.DW_FORM_block2.value -> Form.DW_FORM_block2
    Form.DW_FORM_block4.value -> Form.DW_FORM_block4
    Form.DW_FORM_data2.value -> Form.DW_FORM_data2
    Form.DW_FORM_data4.value -> Form.DW_FORM_data4
    Form.DW_FORM_data8.value -> Form.DW_FORM_data8
    Form.DW_FORM_string.value -> Form.DW_FORM_string
    Form.DW_FORM_block.value -> Form.DW_FORM_block
    Form.DW_FORM_block1.value -> Form.DW_FORM_block1
    Form.DW_FORM_data1.value -> Form.DW_FORM_data1
    Form.DW_FORM_flag.value -> Form.DW_FORM_flag
    Form.DW_FORM_sdata.value -> Form.DW_FORM_sdata
    Form.DW_FORM_strp.value -> Form.DW_FORM_strp
    Form.DW_FORM_udata.value -> Form.DW_FORM_udata
    Form.DW_FORM_ref_addr.value -> Form.DW_FORM_ref_addr
    Form.DW_FORM_ref1.value -> Form.DW_FORM_ref1
    Form.DW_FORM_ref2.value -> Form.DW_FORM_ref2
    Form.DW_FORM_ref4.value -> Form.DW_FORM_ref4
    Form.DW_FORM_ref8.value -> Form.DW_FORM_ref8
    Form.DW_FORM_ref_udata.value -> Form.DW_FORM_ref_udata
    Form.DW_FORM_indirect.value -> Form.DW_FORM_indirect
    Form.DW_FORM_sec_offset.value -> Form.DW_FORM_sec_offset
    Form.DW_FORM_exprloc.value -> Form.DW_FORM_exprloc
    Form.DW_FORM_flag_present.value -> Form.DW_FORM_flag_present
    Form.DW_FORM_ref_sig8.value -> Form.DW_FORM_ref_sig8

    Form.DW_FORM_strx.value -> Form.DW_FORM_strx
    Form.DW_FORM_addrx.value -> Form.DW_FORM_addrx
    Form.DW_FORM_ref_sup4.value -> Form.DW_FORM_ref_sup4
    Form.DW_FORM_strp_sup.value -> Form.DW_FORM_strp_sup
    Form.DW_FORM_data16.value -> Form.DW_FORM_data16
    Form.DW_FORM_line_strp.value -> Form.DW_FORM_line_strp
    Form.DW_FORM_implicit_const.value -> Form.DW_FORM_implicit_const
    Form.DW_FORM_loclistx.value -> Form.DW_FORM_loclistx
    Form.DW_FORM_rnglistx.value -> Form.DW_FORM_rnglistx
    Form.DW_FORM_refsup8.value -> Form.DW_FORM_refsup8
    Form.DW_FORM_strx1.value -> Form.DW_FORM_strx1
    Form.DW_FORM_strx2.value -> Form.DW_FORM_strx2
    Form.DW_FORM_strx4.value -> Form.DW_FORM_strx4
    Form.DW_FORM_strx8.value -> Form.DW_FORM_strx8
    Form.DW_FORM_addrx1.value -> Form.DW_FORM_addrx1
    Form.DW_FORM_addrx2.value -> Form.DW_FORM_addrx2
    Form.DW_FORM_addrx4.value -> Form.DW_FORM_addrx4
    Form.DW_FORM_addrx8.value -> Form.DW_FORM_addrx8
    else -> null

}
