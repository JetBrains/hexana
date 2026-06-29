package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeDebugCompanionScannerTest {

    @Test
    fun parsesElfBuildIdNoteWithNulTerminatedGnuName() {
        val descriptor = ByteArray(20) { (it + 1).toByte() }
        val noteOffset = 16
        val note = ByteArray(12 + 4 + descriptor.size)
        note.putU32Le(0, 4u)
        note.putU32Le(4, descriptor.size.toUInt())
        note.putU32Le(8, 3u)
        byteArrayOf('G'.code.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 0)
            .copyInto(note, destinationOffset = 12)
        descriptor.copyInto(note, destinationOffset = 16)

        val bytes = ByteArray(noteOffset + note.size)
        bytes[5] = 1 // ELF little-endian marker used by the scanner.
        note.copyInto(bytes, destinationOffset = noteOffset)

        val context = NativeDebugCompanionScanner.scan(
            binaryPath = "/tmp/app",
            bytes = bytes.toCommonByteBuffer(),
            sections = listOf(buildIdSection(noteOffset, note.size)),
        )

        assertEquals("0102030405060708090a0b0c0d0e0f1011121314", context.elfBuildId)
        val buildIdCompanion = context.companions.singleOrNull { it.kind == NativeDebugCompanionKind.ElfBuildId }
        assertNotNull(buildIdCompanion)
        assertEquals(NativeDebugCompanionStatus.Found, buildIdCompanion.status)
    }

    private fun buildIdSection(offset: Int, size: Int): ElfSectionHeader =
        ElfSectionHeader(
            index = 1,
            nameOffset = 0u,
            name = ".note.gnu.build-id",
            type = 7u,
            flags = 0u,
            address = 0u,
            fileOffset = offset.toULong(),
            size = size.toULong(),
            link = 0u,
            info = 0u,
            addressAlignment = 4u,
            entrySize = 0u,
            headerRange = NativeByteRange(offset, 0),
            contentRange = NativeByteRange(offset, size),
        )

    private fun ByteArray.putU32Le(offset: Int, value: UInt) {
        this[offset] = (value and 0xFFu).toByte()
        this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
        this[offset + 2] = ((value shr 16) and 0xFFu).toByte()
        this[offset + 3] = ((value shr 24) and 0xFFu).toByte()
    }
}
