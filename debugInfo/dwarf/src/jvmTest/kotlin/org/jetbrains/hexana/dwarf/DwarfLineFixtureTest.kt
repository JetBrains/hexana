@file:OptIn(ExperimentalUnsignedTypes::class)

package org.jetbrains.hexana.dwarf

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DwarfLineFixtureTest {
    @Test
    fun `generated x86_64 DWARF5 line table decodes 8 byte set address`() {
        val bytes = Files.readAllBytes(
            repoRoot()
                .resolve("debugInfo")
                .resolve("dwarf-fixtures")
                .resolve("test-data")
                .resolve("elf")
                .resolve("line-table-64.elf.debug_line.bin")
        ).toCommonByteBuffer()
        val section = DwarfLineSection(0, bytes.size, FixtureDwarfAccessor)

        val header = with(section) {
            bytes.scanLineTable().single()
        }
        assertIs<DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5>(header)

        val program = with(header) {
            bytes.lineProgram()
        }
        val setAddressIndex = program.indexOfFirst { it is LneOpcode.DW_LNE_set_address }
        assertTrue(setAddressIndex >= 0, "fixture must contain DW_LNE_set_address")
        val setAddress = program[setAddressIndex]
        assertIs<LneOpcode.DW_LNE_set_address>(setAddress)
        assertEquals(0x201280uL, setAddress.params.single())
        assertEquals(0x50, program[setAddressIndex + 1].offset)
    }
}

private fun repoRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("debugInfo").resolve("dwarf-fixtures")) }

private object FixtureDwarfAccessor : DwarfAccessor {
    override fun section(name: String): DwarfSection? = null
    override fun debugLineSection(): DwarfLineSection? = null
    override fun debugInfoSection(): DwarfInfoSection? = null
    override fun debugAbbrevSection(): DwarfAbbrevSection? = null
    override fun debugStrSection(): DwarfStrSection? = null
    override fun debugAddrSection(): DwarfAddressSection? = null
}
