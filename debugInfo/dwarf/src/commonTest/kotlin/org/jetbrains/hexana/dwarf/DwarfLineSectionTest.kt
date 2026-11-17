@file:OptIn(ExperimentalUnsignedTypes::class)

package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DwarfLineSectionTest {
    @Test
    fun `DWARF32 v5 line program starts after address and segment size fields`() {
        val bytes = dwarf32V5LineTable(program = intArrayOf(0x19))
        val section = DwarfLineSection(0, bytes.size, EmptyDwarfAccessor)

        val header = with(section) {
            bytes.scanLineTable().single()
        }
        assertIs<DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5>(header)
        assertEquals(34u, header.programOffset)
        val op = with(header) {
            bytes.lineProgram().single()
        }
        assertEquals(34, op.offset)
    }

    @Test
    fun `DWARF32 v5 line program starts at declared header length`() {
        val bytes = dwarf32V5LineTable(
            headerPadding = intArrayOf(0x00),
            program = intArrayOf(0x19)
        )
        val section = DwarfLineSection(0, bytes.size, EmptyDwarfAccessor)

        val header = with(section) {
            bytes.scanLineTable().single()
        }
        assertIs<DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5>(header)
        assertEquals(35u, header.programOffset)
        val op = with(header) {
            bytes.lineProgram().single()
        }
        assertEquals(35, op.offset)
    }

    @Test
    fun `extended line opcode length must include opcode byte`() {
        val bytes = dwarf32V5LineTable(program = intArrayOf(0x00, 0x00))
        val section = DwarfLineSection(0, bytes.size, EmptyDwarfAccessor)
        val header = with(section) {
            bytes.scanLineTable().single()
        }

        assertFailsWith<IllegalArgumentException> {
            with(header) {
                bytes.lineProgram()
            }
        }
    }

    @Test
    fun `set address rejects payload that does not match header address size`() {
        val bytes = dwarf32V5LineTable(
            addressSize = 8,
            program = intArrayOf(
                0x00, // extended opcode marker
                0x05, // opcode byte + 4 address bytes
                0x02, // DW_LNE_set_address
                0x78,
                0x56,
                0x34,
                0x12,
            )
        )
        val section = DwarfLineSection(0, bytes.size, EmptyDwarfAccessor)
        val header = with(section) {
            bytes.scanLineTable().single()
        }

        assertFailsWith<IllegalArgumentException> {
            with(header) {
                bytes.lineProgram()
            }
        }
    }

    @Test
    fun `set address accepts unusual representable address byte width`() {
        val bytes = dwarf32V5LineTable(
            addressSize = 3,
            program = intArrayOf(
                0x00, // extended opcode marker
                0x04, // opcode byte + 3 address bytes
                0x02, // DW_LNE_set_address
                0x78,
                0x56,
                0x34,
            )
        )
        val section = DwarfLineSection(0, bytes.size, EmptyDwarfAccessor)
        val header = with(section) {
            bytes.scanLineTable().single()
        }

        val op = with(header) {
            bytes.lineProgram().single()
        }

        assertIs<LneOpcode.DW_LNE_set_address>(op)
        assertEquals(0x345678uL, op.params.single())
    }

    @Test
    fun `set address skips segment selector before address when present`() {
        val bytes = dwarf32V5LineTable(
            addressSize = 4,
            segmentSelectorSize = 2,
            program = intArrayOf(
                0x00, // extended opcode marker
                0x07, // opcode byte + 2 segment selector bytes + 4 address bytes
                0x02, // DW_LNE_set_address
                0xaa,
                0xbb,
                0x78,
                0x56,
                0x34,
                0x12,
            )
        )
        val section = DwarfLineSection(0, bytes.size, EmptyDwarfAccessor)
        val header = with(section) {
            bytes.scanLineTable().single()
        }

        val op = with(header) {
            bytes.lineProgram().single()
        }

        assertIs<LneOpcode.DW_LNE_set_address>(op)
        assertEquals(0x12345678uL, op.params.single())
    }
}

private fun dwarf32V5LineTable(
    addressSize: Int = 8,
    segmentSelectorSize: Int = 0,
    headerPadding: IntArray = intArrayOf(),
    program: IntArray,
): org.jetbrains.hexana.io.CommonByteBuffer {
    val prologLength = 22 + headerPadding.size
    val unitLength = 30 + headerPadding.size + program.size
    val header = byteArrayOf(
        unitLength.toByte(), 0x00, 0x00, 0x00, // unit_length
        0x05, 0x00, // version
        addressSize.toByte(),
        segmentSelectorSize.toByte(),
        prologLength.toByte(), 0x00, 0x00, 0x00, // header_length
        0x01, // minimum_instruction_length
        0x01, // maximum_operations_per_instruction
        0x01, // default_is_stmt
        0xfb.toByte(), // line_base = -5
        0x0e, // line_range
        0x0d, // opcode_base
        0x00, 0x01, 0x01, 0x01, 0x01, 0x00,
        0x00, 0x00, 0x01, 0x00, 0x00, 0x01, // standard_opcode_lengths
        0x00, // directory_entry_format_count
        0x00, // directories_count
        0x00, // file_name_entry_format_count
        0x00, // file_names_count
    )
    return (
        header +
            headerPadding.map { it.toByte() }.toByteArray() +
            program.map { it.toByte() }.toByteArray()
        ).toCommonByteBuffer()
}

private object EmptyDwarfAccessor : DwarfAccessor {
    override fun section(name: String): DwarfSection? = null
    override fun debugLineSection(): DwarfLineSection? = null
    override fun debugInfoSection(): DwarfInfoSection? = null
    override fun debugAbbrevSection(): DwarfAbbrevSection? = null
    override fun debugStrSection(): DwarfStrSection? = null
    override fun debugAddrSection(): DwarfAddressSection? = null
    override fun debugLocSection(): DwarfLocSection? = null
}
