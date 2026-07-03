package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer

interface DwarfUnit {
    val version: Int
    val addressSize: UByte
    // DWARF section offsets are sized by the DWARF32/DWARF64 format, not by the
    // target address size. See DWARF v5 section 7.4 and 7.5.5.
    // https://dwarfstd.org/doc/DWARF5.pdf
    val offsetSize: UByte get() = addressSize
}

fun CommonByteBuffer.readForm(
    form: Form,
    header: DwarfUnit
): Any = when (form) {
    Form.DW_FORM_flag, Form.DW_FORM_ref1, Form.DW_FORM_data1, Form.DW_FORM_strx1 -> get().toUByte()
    Form.DW_FORM_ref2, Form.DW_FORM_data2, Form.DW_FORM_strx2 ->  getShortLE().toUShort()
    Form.DW_FORM_ref4, Form.DW_FORM_data4, Form.DW_FORM_strx4 ->  getIntLE().toUInt()
    Form.DW_FORM_ref8, Form.DW_FORM_data8, Form.DW_FORM_strx8 ->  getLongLE().toULong()
    Form.DW_FORM_data16 -> ByteArray(16).also { get(it, 0, 16) }
    Form.DW_FORM_strp, Form.DW_FORM_sec_offset -> when (header.offsetSize) {
        4.toUByte() -> getIntLE().toUInt()
        8.toUByte() -> getLongLE().toULong()
        else -> throw IllegalArgumentException("Unsupported DWARF offset size: ${header.offsetSize}")
    }

    Form.DW_FORM_ref_addr -> when (val size = if (header.version <= 2) header.addressSize else header.offsetSize) {
        4.toUByte() -> getIntLE().toUInt()
        8.toUByte() -> getLongLE().toULong()
        else -> throw IllegalArgumentException("Unsupported DWARF reference address size: $size")
    }

    Form.DW_FORM_addr -> when (header.addressSize) {
        4.toUByte() ->  getIntLE().toUInt()
        8.toUByte() ->  getLongLE().toULong()
        else -> throw IllegalArgumentException("Unsupported address size: ${header.addressSize}")
    }

    Form.DW_FORM_flag_present -> true
    Form.DW_FORM_string -> readStringZ()

    Form.DW_FORM_exprloc -> {
        val size = getULEB128()
        val buffer = ByteArray(size.toInt())
        get(buffer, 0, size.toInt())
        buffer
    }

    Form.DW_FORM_udata,
    Form.DW_FORM_sdata -> {
        getULEB128()
    }

    Form.DW_FORM_block1 -> {
        val length = get().toUByte().toInt()
        val buffer = ByteArray(length)
        get(buffer, 0, length)
        buffer
    }

    Form.DW_FORM_block2 -> {
        val length = getShortLE().toUShort().toInt()
        val buffer = ByteArray(length)
        get(buffer, 0, length)
        buffer
    }

    Form.DW_FORM_block4 -> {
        val length = getIntLE().toUInt().toInt()
        val buffer = ByteArray(length)
        get(buffer, 0, length)
        buffer
    }

    Form.DW_FORM_block -> {
        val length = getULEB128().toInt()
        val buffer = ByteArray(length)
        get(buffer, 0, length)
        buffer
    }

    Form.DW_FORM_addrx -> getULEB128()
    // read from v4 .debug_str
    // read from v5 .debug_line_str
    Form.DW_FORM_line_strp -> {
        when (header.offsetSize) {
             4.toUByte() -> getIntLE().toUInt().toInt()
             8.toUByte() -> getLongLE().toULong().toIntExact("DW_FORM_line_strp")
            else -> throw IllegalArgumentException("Unsupported DWARF offset size: ${header.offsetSize}")
        }
    }
    else -> TODO("Unsupported form: ${form::class.simpleName}")
}

private fun ULong.toIntExact(formName: String): Int {
    require(this <= Int.MAX_VALUE.toULong()) {
        "$formName offset $this does not fit into Int"
    }
    return toInt()
}
