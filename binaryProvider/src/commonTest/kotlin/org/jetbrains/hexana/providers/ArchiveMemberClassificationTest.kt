package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classification reads only a member's leading header bytes. These tests place a member's
 * content at a non-zero offset inside a larger buffer (mimicking a real archive layout) and
 * assert it is classified from the member's own start — not the buffer's offset 0.
 */
class ArchiveMemberClassificationTest {

    private fun classifyAt(member: ByteArray, leadPadding: Int = 16): ArchiveMemberClassification {
        val buffer = ByteArray(leadPadding + member.size + 8)
        member.copyInto(buffer, leadPadding)
        return classifyArchiveMember(
            buffer.toCommonByteBuffer(),
            NativeByteRange(offset = leadPadding, size = member.size),
        )
    }

    @Test
    fun `classifies an ELF64 little-endian member`() {
        // \x7fELF, EI_CLASS=2 (64-bit), EI_DATA=1 (LE), then padding past e_machine@18.
        val elf = ByteArray(32)
        elf[0] = 0x7F; elf[1] = 0x45; elf[2] = 0x4C; elf[3] = 0x46
        elf[4] = 0x02; elf[5] = 0x01
        elf[18] = 0x3E // e_machine = x86_64 (LE)
        val cls = classifyAt(elf)
        assertEquals(ArchiveMemberKind.ELF, cls.kind)
        assertTrue(cls.openable)
    }

    @Test
    fun `classifies a Mach-O 64-bit little-endian member`() {
        // CF FA ED FE = Mach-O 64-bit little-endian magic.
        val macho = byteArrayOf(
            0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte(),
            0x0C, 0x00, 0x00, 0x01, // cpu_type ARM64 (LE)
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        val cls = classifyAt(macho)
        assertEquals(ArchiveMemberKind.MACH_O, cls.kind)
        assertTrue(cls.openable)
    }

    @Test
    fun `flags a Windows short-import record as non-openable`() {
        // Sig1 = 0x0000 (machine UNKNOWN), Sig2 = 0xFFFF.
        val shortImport = byteArrayOf(
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val cls = classifyAt(shortImport)
        assertEquals(ArchiveMemberKind.SHORT_IMPORT, cls.kind)
        assertFalse(cls.openable)
    }

    @Test
    fun `reports unknown for unrecognized member bytes`() {
        val garbage = ByteArray(32) { 0x5A } // 'Z' repeated — no known magic
        val cls = classifyAt(garbage)
        assertEquals(ArchiveMemberKind.UNKNOWN, cls.kind)
        assertFalse(cls.openable)
    }

    @Test
    fun `reports unknown for a member shorter than four bytes`() {
        val cls = classifyAt(byteArrayOf(0x7F, 0x45))
        assertEquals(ArchiveMemberKind.UNKNOWN, cls.kind)
    }
}
