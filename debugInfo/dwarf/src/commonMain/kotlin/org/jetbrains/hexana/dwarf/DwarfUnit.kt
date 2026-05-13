package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer

interface DwarfUnit {
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
    Form.DW_FORM_flag, Form.DW_FORM_ref1, Form.DW_FORM_data1, Form.DW_FORM_strx1 -> readUByte()
    Form.DW_FORM_ref2, Form.DW_FORM_data2, Form.DW_FORM_strx2 ->  readUShort()
    Form.DW_FORM_ref4, Form.DW_FORM_data4, Form.DW_FORM_strx4 ->  readUInt()
    Form.DW_FORM_ref8, Form.DW_FORM_data8, Form.DW_FORM_strx8 ->  readULong()
    Form.DW_FORM_data16 -> ByteArray(16).also { get(it, 0, 16) }
    Form.DW_FORM_strp, Form.DW_FORM_sec_offset -> when (header.offsetSize) {
        4.toUByte() -> readUInt()
        8.toUByte() -> readULong()
        else -> throw IllegalArgumentException("Unsupported DWARF offset size: ${header.offsetSize}")
    }

    Form.DW_FORM_ref_addr -> when (header.addressSize) {
        4.toUByte() -> readUInt()
        8.toUByte() -> readULong()
        else -> throw IllegalArgumentException("Unsupported address size: ${header.addressSize}")
    }

    Form.DW_FORM_addr -> when (header.addressSize) {
        4.toUByte() ->  readUInt()
        8.toUByte() ->  readULong()
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
        val length = readUByte().toInt()
        val buffer = ByteArray(length)
        get(buffer, 0, length)
        buffer
    }

    Form.DW_FORM_block2 -> {
        val length = readUShort().toInt()
        val buffer = ByteArray(length)
        get(buffer, 0, length)
        buffer
    }

    Form.DW_FORM_block4 -> {
        val length = readUInt().toInt()
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
             4.toUByte() -> readUInt().toInt()
             8.toUByte() -> readULong().toInt()
            else -> throw IllegalArgumentException("Unsupported DWARF offset size: ${header.offsetSize}")
        }
    }
    else -> TODO("Unsupported form: ${form::class.simpleName}")
}
