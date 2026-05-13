@file:OptIn(ExperimentalUnsignedTypes::class)

package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class DwarfSourceLocationResolverTest {
    @Test
    fun `line table locations preserve 64-bit addresses`() {
        val bytes = dwarf32V4LineTableWithAddress(0x80000001uL)
        val section = DwarfLineSection(0, bytes.size, LineTableOnlyDwarfAccessor(null))
        val accessor = LineTableOnlyDwarfAccessor(section)
        val resolver = DwarfSourceLocationResolver(accessor)

        val locations = with(resolver) {
            bytes.resolveLineTableLocations()
        }

        assertEquals(1, locations.size)
        assertEquals(0x80000001uL, locations.single().offset)
        assertEquals("main.c", locations.single().sourceLocation.file)
    }

    @Test
    fun `line table locations skip end sequence rows`() {
        val bytes = dwarf32V4LineTableWithAddress(
            address = 0x10uL,
            programSuffix = byteArrayOf(
                0x00, // extended opcode marker
                0x01, // opcode byte only
                0x01, // DW_LNE_end_sequence
            )
        )
        val section = DwarfLineSection(0, bytes.size, LineTableOnlyDwarfAccessor(null))
        val accessor = LineTableOnlyDwarfAccessor(section)
        val resolver = DwarfSourceLocationResolver(accessor)

        val locations = with(resolver) {
            bytes.resolveLineTableLocations()
        }

        assertEquals(1, locations.size)
        assertEquals(0x10uL, locations.single().offset)
    }

    @Test
    fun `line table locations preserve unix root directory`() {
        val bytes = dwarf32V4LineTableWithAddress(
            address = 0x10uL,
            directories = listOf("/"),
            fileName = "main.c",
            directoryIndex = 1,
        )
        val section = DwarfLineSection(0, bytes.size, LineTableOnlyDwarfAccessor(null))
        val accessor = LineTableOnlyDwarfAccessor(section)
        val resolver = DwarfSourceLocationResolver(accessor)

        val locations = with(resolver) {
            bytes.resolveLineTableLocations()
        }

        assertEquals("/main.c", locations.single().sourceLocation.file)
    }

    @Test
    fun `line table locations keep Windows absolute file paths absolute`() {
        val bytes = dwarf32V4LineTableWithAddress(
            address = 0x10uL,
            directories = listOf("ignored"),
            fileName = "C:\\src\\main.c",
            directoryIndex = 1,
        )
        val section = DwarfLineSection(0, bytes.size, LineTableOnlyDwarfAccessor(null))
        val accessor = LineTableOnlyDwarfAccessor(section)
        val resolver = DwarfSourceLocationResolver(accessor)

        val locations = with(resolver) {
            bytes.resolveLineTableLocations()
        }

        assertEquals("C:/src/main.c", locations.single().sourceLocation.file)
    }

    @Test
    fun `line table locations do not treat non-ascii drive prefix as Windows absolute path`() {
        val bytes = dwarf32V4LineTableWithAddress(
            address = 0x10uL,
            directories = listOf("root"),
            fileName = "Å:\\src\\main.c",
            directoryIndex = 1,
        )
        val section = DwarfLineSection(0, bytes.size, LineTableOnlyDwarfAccessor(null))
        val accessor = LineTableOnlyDwarfAccessor(section)
        val resolver = DwarfSourceLocationResolver(accessor)

        val locations = with(resolver) {
            bytes.resolveLineTableLocations()
        }

        assertEquals("root/Å:/src/main.c", locations.single().sourceLocation.file)
    }
}

private fun dwarf32V4LineTableWithAddress(
    address: ULong,
    directories: List<String> = emptyList(),
    fileName: String = "main.c",
    directoryIndex: Int = 0,
    programSuffix: ByteArray = byteArrayOf(),
) = buildList {
    val headerBody = dwarf32V4LineTableHeaderBody(directories, fileName, directoryIndex)
    val program = dwarfSetAddressProgram(address) + programSuffix
    addAll(uint32(2 + 4 + headerBody.size + program.size))
    add(0x04) // version
    add(0x00)
    addAll(uint32(headerBody.size))
    addAll(headerBody.asIterable())
    addAll(program.asIterable())
}.map { it.toByte() }.toByteArray().toCommonByteBuffer()

private fun dwarf32V4LineTableHeaderBody(
    directories: List<String>,
    fileName: String,
    directoryIndex: Int,
): ByteArray = buildList {
    add(0x01) // minimum_instruction_length
    add(0x01) // maximum_operations_per_instruction
    add(0x01) // default_is_stmt
    add(0xfb) // line_base = -5
    add(0x0e) // line_range
    add(0x02) // opcode_base
    add(0x00) // standard_opcode_lengths
    directories.forEach { addStringZ(it) }
    add(0x00) // include_directories terminator
    addStringZ(fileName)
    add(directoryIndex) // directory index
    add(0x00) // modification time
    add(0x00) // file size
    add(0x00) // file_names terminator
}.map { it.toByte() }.toByteArray()

private fun dwarfSetAddressProgram(address: ULong): ByteArray =
    byteArrayOf(
        0x00, // extended opcode marker
        0x09, // opcode byte + 8 address bytes
        0x02, // DW_LNE_set_address
        (address and 0xffuL).toByte(),
        ((address shr 8) and 0xffuL).toByte(),
        ((address shr 16) and 0xffuL).toByte(),
        ((address shr 24) and 0xffuL).toByte(),
        ((address shr 32) and 0xffuL).toByte(),
        ((address shr 40) and 0xffuL).toByte(),
        ((address shr 48) and 0xffuL).toByte(),
        ((address shr 56) and 0xffuL).toByte(),
        0x01, // DW_LNS_copy
    )

private fun uint32(value: Int): List<Int> = listOf(
    value and 0xff,
    (value shr 8) and 0xff,
    (value shr 16) and 0xff,
    (value shr 24) and 0xff,
)

private fun MutableList<Int>.addStringZ(value: String) {
    value.encodeToByteArray().forEach { add(it.toUByte().toInt()) }
    add(0)
}

private class LineTableOnlyDwarfAccessor(private val lineSection: DwarfLineSection?) : DwarfAccessor {
    override fun section(name: String): DwarfSection? = null
    override fun debugLineSection(): DwarfLineSection? = lineSection
    override fun debugInfoSection(): DwarfInfoSection? = null
    override fun debugAbbrevSection(): DwarfAbbrevSection? = null
    override fun debugStrSection(): DwarfStrSection? = null
    override fun debugAddrSection(): DwarfAddressSection? = null
}
