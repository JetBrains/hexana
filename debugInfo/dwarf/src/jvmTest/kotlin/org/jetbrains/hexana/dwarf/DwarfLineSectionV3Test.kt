@file:OptIn(ExperimentalUnsignedTypes::class)

package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Regression test for DWARF v2/v3 line-program header parsing.
 *
 * GCC emits version-2 or version-3 line-program headers even inside -gdwarf-4/-gdwarf-5
 * compilations. The v4 reader was incorrectly consuming the extra
 * maximum_operations_per_instruction byte that does not exist in v2/v3, causing a
 * one-byte shift that corrupted opcode_base and produced ByteArray(-1).
 *
 * Blob layout (DWARF 32-bit, version 3):
 *   unit_length     = 53   (4 bytes LE, excludes itself)
 *   version         = 3    (2 bytes LE)
 *   header_length   = 34   (4 bytes LE)
 *   min_inst_len    = 2
 *   default_is_stmt = 1
 *   line_base       = -5   (0xFB)
 *   line_range      = 14
 *   opcode_base     = 13
 *   std_opcode_lens = {0,1,1,1,1,0,0,0,1,0,0,1}  (12 bytes)
 *   include_dirs    = "/src\0" + terminator \0
 *   file_names      = "boot.c\0" dir=1 mtime=0 size=0 + terminator \0
 *   program:
 *     DW_LNE_set_address 0x00000524
 *     DW_LNS_advance_line +9        -> line register = 10
 *     special opcode 18             -> emits row (addr=0x524, line=10)
 *     DW_LNE_end_sequence
 */
class DwarfLineSectionV3Test {

    // Hand-assembled 57-byte DWARF32 v3 .debug_line blob.
    private val v3Blob = byteArrayOf(
        // unit_length = 53
        0x35, 0x00, 0x00, 0x00,
        // version = 3
        0x03, 0x00,
        // header_length = 34
        0x22, 0x00, 0x00, 0x00,
        // min_inst_len=2, default_is_stmt=1, line_base=-5(0xFB), line_range=14, opcode_base=13
        0x02, 0x01, 0xFB.toByte(), 0x0E, 0x0D,
        // standard_opcode_lengths[0..11] = {0,1,1,1,1,0,0,0,1,0,0,1}
        0x00, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x01,
        // include_directories: "/src\0", then list-terminator \0
        0x2F, 0x73, 0x72, 0x63, 0x00, 0x00,
        // file_names: "boot.c\0", dir_index=1, mtime=0, size=0, list-terminator \0
        0x62, 0x6F, 0x6F, 0x74, 0x2E, 0x63, 0x00, 0x01, 0x00, 0x00, 0x00,
        // DW_LNE_set_address(0x524): opcode=0x00, length=5, DW_LNE=0x02, addr=0x00000524 LE
        0x00, 0x05, 0x02, 0x24, 0x05, 0x00, 0x00,
        // DW_LNS_advance_line(+9): opcode=0x03, SLEB128=0x09
        0x03, 0x09,
        // special opcode 18 (line_delta=0, addr_advance=0 -> emits row)
        0x12,
        // DW_LNE_end_sequence: opcode=0x00, length=1, DW_LNE=0x01
        0x00, 0x01, 0x01
    )

    @Test
    fun `DWARF v3 line table header parses without error`() {
        val buf = v3Blob.toCommonByteBuffer()
        val section = DwarfLineSection(0, v3Blob.size, FixtureDwarfAccessorV3)

        val headers = with(section) { buf.scanLineTable() }

        assertEquals(1, headers.size, "must parse exactly one line unit header")
        val header = headers.single()
        assertIs<DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4>(header)
        assertEquals(1, header.includeDirectories.size)
        assertEquals("/src", header.includeDirectories[0])
        assertEquals(1, header.includeFiles.size)
        assertEquals("boot.c", header.includeFiles[0].fileName)
    }

    @Test
    fun `DWARF v3 line program emits row at address 0x524 line 10`() {
        val buf = v3Blob.toCommonByteBuffer()
        val section = DwarfLineSection(0, v3Blob.size, FixtureDwarfAccessorV3)

        val header = with(section) { buf.scanLineTable() }.single()
        assertIs<DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4>(header)

        val program = with(header) { buf.lineProgram() }
        // The program must emit exactly one non-end-sequence row
        val emittedRows = program.filterIsInstance<LnsSpecialOpcode>()
        assertEquals(1, emittedRows.size, "must emit exactly one row via special opcode")
    }

    @Test
    fun `DWARF v3 resolveState maps file=1 to correct path`() {
        val buf = v3Blob.toCommonByteBuffer()
        val section = DwarfLineSection(0, v3Blob.size, FixtureDwarfAccessorV3)

        val header = with(section) { buf.scanLineTable() }.single()
        assertIs<DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4>(header)

        val resolver = DwarfSourceLocationResolver(FixtureDwarfAccessorV3)
        val state = LineRow(offset = 0x524uL, line = 10, column = 0, file = 1)
        val location = with(resolver) { buf.resolveState(header, state, compDir = null) }

        assertNotNull(location, "resolveState must return a non-null SourceLocation for a valid v3 header")
        assertEquals("/src/boot.c", location.file)
        assertEquals(10, location.line)
    }
}

private object FixtureDwarfAccessorV3 : DwarfAccessor {
    override fun section(name: String): DwarfSection? = null
    override fun debugLineSection(): DwarfLineSection? = null
    override fun debugInfoSection(): DwarfInfoSection? = null
    override fun debugAbbrevSection(): DwarfAbbrevSection? = null
    override fun debugStrSection(): DwarfStrSection? = null
    override fun debugAddrSection(): DwarfAddressSection? = null
    override fun debugLocSection(): DwarfLocSection? = null
}
