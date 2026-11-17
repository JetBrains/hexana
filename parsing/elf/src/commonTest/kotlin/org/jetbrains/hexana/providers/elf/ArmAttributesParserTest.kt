package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArmAttributesParserTest {

    // -------------------------------------------------------------------------
    // Helpers — build a minimal .ARM.attributes section in a ByteArray.
    //
    // Format (ARM IHI0045):
    //   [0]       'A' (0x41)  — format version
    //   [1..4]    u32LE section length (includes these 4 bytes)
    //   [5..]     NUL-terminated vendor name
    //   after NUL: subsections — (ULEB128 tag) (u32LE size incl. 4 bytes) (attributes)
    //   Attribute: (ULEB128 tag) (ULEB128 value)
    // -------------------------------------------------------------------------

    private fun buildAeabiSection(cpuArchProfile: Byte): ByteArray {
        // Attribute payload: tag_7 (ULEB128 0x07) + value (ULEB128 cpuArchProfile)
        val attrTag = 0x07.toByte()
        val attrValue = cpuArchProfile

        // Tag_File subsection: ULEB128(1) + u32LE(size) + attributes
        // size field includes the 4 bytes of the size field itself + attribute bytes
        val attrBytes = byteArrayOf(attrTag, attrValue)
        val subSize = 4 + attrBytes.size  // u32LE size field + attr payload
        val subsection = byteArrayOf(
            0x01,  // ULEB128 Tag_File = 1
            subSize.toByte(), 0, 0, 0,  // u32LE size (little-endian, fits in 1 byte here)
            *attrBytes,
        )

        // NUL-terminated vendor name "aeabi\0"
        val vendorName = byteArrayOf(0x61, 0x65, 0x61, 0x62, 0x69, 0x00)  // a e a b i NUL

        // Section body: vendor name + subsections
        val body = vendorName + subsection
        // Section length = 4 (length field itself) + body
        val sectionLength = 4 + body.size

        return byteArrayOf(
            0x41,  // format version 'A'
            sectionLength.toByte(), 0, 0, 0,  // u32LE section length
            *body,
        )
    }

    private fun sectionHeaderForBytes(bytes: ByteArray, offset: Int): ElfSectionHeader =
        ElfSectionHeader(
            index = 0,
            nameOffset = 0u,
            name = ".ARM.attributes",
            type = 0x70000003u,
            flags = 0uL,
            address = 0uL,
            fileOffset = offset.toULong(),
            size = bytes.size.toULong() - offset.toULong(),
            link = 0u,
            info = 0u,
            addressAlignment = 1uL,
            entrySize = 0uL,
            headerRange = NativeByteRange(0, 0),
            contentRange = NativeByteRange(offset, bytes.size - offset),
        )

    @Test
    fun `parses Tag_CPU_arch_profile M for Cortex-M`() {
        val sectionBytes = buildAeabiSection(0x4D)  // 'M'
        val buf = sectionBytes.toCommonByteBuffer()
        val section = sectionHeaderForBytes(sectionBytes, 0)

        val profile = ArmAttributesParser.parseCpuArchProfile(buf, section)

        assertEquals('M', profile)
    }

    @Test
    fun `parses Tag_CPU_arch_profile A for application profile`() {
        val sectionBytes = buildAeabiSection(0x41)  // 'A'
        val buf = sectionBytes.toCommonByteBuffer()
        val section = sectionHeaderForBytes(sectionBytes, 0)

        assertEquals('A', ArmAttributesParser.parseCpuArchProfile(buf, section))
    }

    @Test
    fun `parses Tag_CPU_arch_profile R for real-time profile`() {
        val sectionBytes = buildAeabiSection(0x52)  // 'R'
        val buf = sectionBytes.toCommonByteBuffer()
        val section = sectionHeaderForBytes(sectionBytes, 0)

        assertEquals('R', ArmAttributesParser.parseCpuArchProfile(buf, section))
    }

    @Test
    fun `returns null for malformed input — wrong version byte`() {
        val sectionBytes = buildAeabiSection(0x4D).also { it[0] = 0x42 }  // 'B' not 'A'
        val buf = sectionBytes.toCommonByteBuffer()
        val section = sectionHeaderForBytes(sectionBytes, 0)

        assertNull(ArmAttributesParser.parseCpuArchProfile(buf, section))
    }

    @Test
    fun `returns null for empty section content`() {
        val buf = ByteArray(0).toCommonByteBuffer()
        val section = ElfSectionHeader(
            index = 0,
            nameOffset = 0u,
            name = ".ARM.attributes",
            type = 0x70000003u,
            flags = 0uL,
            address = 0uL,
            fileOffset = 0uL,
            size = 0uL,
            link = 0u,
            info = 0u,
            addressAlignment = 1uL,
            entrySize = 0uL,
            headerRange = NativeByteRange(0, 0),
            contentRange = NativeByteRange(0, 0),
        )

        assertNull(ArmAttributesParser.parseCpuArchProfile(buf, section))
    }

    @Test
    fun `returns null when contentRange is null`() {
        val buf = buildAeabiSection(0x4D).toCommonByteBuffer()
        val section = ElfSectionHeader(
            index = 0,
            nameOffset = 0u,
            name = ".ARM.attributes",
            type = 0x70000003u,
            flags = 0uL,
            address = 0uL,
            fileOffset = 0uL,
            size = 1uL,
            link = 0u,
            info = 0u,
            addressAlignment = 1uL,
            entrySize = 0uL,
            headerRange = NativeByteRange(0, 0),
            contentRange = null,
        )

        assertNull(ArmAttributesParser.parseCpuArchProfile(buf, section))
    }

    @Test
    fun `returns null for truncated section`() {
        val sectionBytes = buildAeabiSection(0x4D).copyOf(3)  // truncate
        val buf = sectionBytes.toCommonByteBuffer()
        val section = sectionHeaderForBytes(sectionBytes, 0)

        assertNull(ArmAttributesParser.parseCpuArchProfile(buf, section))
    }
}
