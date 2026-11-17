package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DwarfFormTest {
    @Test
    fun `line string offsets use DWARF offset size instead of address size`() {
        val buffer = byteArrayOf(
            0x78,
            0x56,
            0x34,
            0x12,
            0x7f,
        ).toCommonByteBuffer()
        val unit = object : DwarfUnit {
            override val version: Int = 5
            override val addressSize: UByte = 8.toUByte()
            override val offsetSize: UByte = 4.toUByte()
        }

        val formValue = buffer.readForm(Form.DW_FORM_line_strp, unit)

        assertEquals(0x12345678, formValue)
        assertEquals(4, buffer.position())
    }

    @Test
    fun `section offsets use DWARF offset size while addresses use address size`() {
        val sectionOffsetBytes = byteArrayOf(
            0x44,
            0x33,
            0x22,
            0x11,
            0x7f,
        ).toCommonByteBuffer()
        val addressBytes = byteArrayOf(
            0x08,
            0x07,
            0x06,
            0x05,
            0x04,
            0x03,
            0x02,
            0x01,
        ).toCommonByteBuffer()
        val unit = object : DwarfUnit {
            override val version: Int = 5
            override val addressSize: UByte = 8.toUByte()
            override val offsetSize: UByte = 4.toUByte()
        }

        assertEquals(0x11223344u, sectionOffsetBytes.readForm(Form.DW_FORM_sec_offset, unit))
        assertEquals(4, sectionOffsetBytes.position())
        assertEquals(0x0102030405060708uL, addressBytes.readForm(Form.DW_FORM_addr, unit))
        assertEquals(8, addressBytes.position())
    }

    @Test
    fun `reference addresses use offset size for DWARF v3 and newer`() {
        val buffer = byteArrayOf(
            0x44,
            0x33,
            0x22,
            0x11,
            0x7f,
        ).toCommonByteBuffer()
        val unit = object : DwarfUnit {
            override val version: Int = 4
            override val addressSize: UByte = 8.toUByte()
            override val offsetSize: UByte = 4.toUByte()
        }

        assertEquals(0x11223344u, buffer.readForm(Form.DW_FORM_ref_addr, unit))
        assertEquals(4, buffer.position())
    }

    @Test
    fun `reference addresses use address size for DWARF v2`() {
        val buffer = byteArrayOf(
            0x08,
            0x07,
            0x06,
            0x05,
            0x04,
            0x03,
            0x02,
            0x01,
        ).toCommonByteBuffer()
        val unit = object : DwarfUnit {
            override val version: Int = 2
            override val addressSize: UByte = 8.toUByte()
            override val offsetSize: UByte = 4.toUByte()
        }

        assertEquals(0x0102030405060708uL, buffer.readForm(Form.DW_FORM_ref_addr, unit))
        assertEquals(8, buffer.position())
    }

    @Test
    fun `line string offset rejects Int overflow`() {
        val buffer = byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x80.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
        ).toCommonByteBuffer()
        val unit = object : DwarfUnit {
            override val version: Int = 5
            override val addressSize: UByte = 8.toUByte()
            override val offsetSize: UByte = 8.toUByte()
        }

        assertFailsWith<IllegalArgumentException> {
            buffer.readForm(Form.DW_FORM_line_strp, unit)
        }
    }
}
