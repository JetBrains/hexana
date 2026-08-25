package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [ElfParser.parseGnuBuildId].
 *
 * The ELF note format per entry (all fields in file endianness):
 *   u32 namesz, u32 descsz, u32 type
 *   name bytes  (padded to 4-byte alignment)
 *   desc bytes  (padded to 4-byte alignment)
 *
 * NT_GNU_BUILD_ID: name = "GNU\0" (namesz=4), type = 3.
 */
class ElfGnuBuildIdTest {

    // Builds a synthetic ELF header (32-bit LE) for use with the parser.
    private fun makeHeader(endian: NativeBinaryEndian = NativeBinaryEndian.Little): ElfHeader =
        ElfHeader(
            wordSize = NativeBinaryWordSize.Bits32,
            endian = endian,
            osAbi = 0, abiVersion = 0, fileType = 2, machine = 0x28, version = 1u,
            entryPoint = 0uL, programHeaderOffset = 0uL, sectionHeaderOffset = 0uL,
            flags = 0u, headerSize = 52, programHeaderEntrySize = 0,
            programHeaderCount = 0, sectionHeaderEntrySize = 0, sectionHeaderCount = 0,
            sectionNameStringTableIndex = 0,
            identRange = NativeByteRange(0, 0),
            headerRange = NativeByteRange(0, 0),
            fieldRanges = ElfHeaderFieldRanges(
                ident = NativeByteRange(0, 0),
                osAbi = NativeByteRange(0, 0),
                abiVersion = NativeByteRange(0, 0),
                fileType = NativeByteRange(0, 0),
                machine = NativeByteRange(0, 0),
                version = NativeByteRange(0, 0),
                entryPoint = NativeByteRange(0, 0),
                programHeaderOffset = NativeByteRange(0, 0),
                sectionHeaderOffset = NativeByteRange(0, 0),
                flags = NativeByteRange(0, 0),
                headerSize = NativeByteRange(0, 0),
                programHeaderEntrySize = NativeByteRange(0, 0),
                programHeaderCount = NativeByteRange(0, 0),
                sectionHeaderEntrySize = NativeByteRange(0, 0),
                sectionHeaderCount = NativeByteRange(0, 0),
                sectionNameStringTableIndex = NativeByteRange(0, 0),
            ),
        )

    // Writes a u32 little-endian at [offset] into [buf].
    private fun ByteArray.putU32Le(offset: Int, value: UInt) {
        this[offset] = (value and 0xFFu).toByte()
        this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
        this[offset + 2] = ((value shr 16) and 0xFFu).toByte()
        this[offset + 3] = ((value shr 24) and 0xFFu).toByte()
    }

    private fun ByteArray.putU32Be(offset: Int, value: UInt) {
        this[offset] = ((value shr 24) and 0xFFu).toByte()
        this[offset + 1] = ((value shr 16) and 0xFFu).toByte()
        this[offset + 2] = ((value shr 8) and 0xFFu).toByte()
        this[offset + 3] = (value and 0xFFu).toByte()
    }

    /**
     * Builds raw bytes for a single GNU build-id note entry.
     * namesz=4, name="GNU\0", type=3, desc=buildIdBytes.
     */
    private fun gnuBuildIdNoteBytes(buildIdBytes: ByteArray, bigEndian: Boolean = false): ByteArray {
        val namesz = 4
        val namePadded = 4
        val descsz = buildIdBytes.size
        val descPadded = (descsz + 3) and -4
        val total = 12 + namePadded + descPadded
        val buf = ByteArray(total)
        if (bigEndian) {
            buf.putU32Be(0, namesz.toUInt())
            buf.putU32Be(4, descsz.toUInt())
            buf.putU32Be(8, 3u)
        } else {
            buf.putU32Le(0, namesz.toUInt())
            buf.putU32Le(4, descsz.toUInt())
            buf.putU32Le(8, 3u)
        }
        buf[12] = 'G'.code.toByte()
        buf[13] = 'N'.code.toByte()
        buf[14] = 'U'.code.toByte()
        buf[15] = 0
        for (i in buildIdBytes.indices) buf[16 + i] = buildIdBytes[i]
        return buf
    }

    private fun makeSection(name: String, bytes: ByteArray, fileOffset: Int = 0): ElfSectionHeader =
        ElfSectionHeader(
            index = 0,
            nameOffset = 0u,
            name = name,
            type = ElfSectionType.Note,
            flags = 0uL,
            address = 0uL,
            fileOffset = fileOffset.toULong(),
            size = bytes.size.toULong(),
            link = 0u,
            info = 0u,
            addressAlignment = 0uL,
            entrySize = 0uL,
            headerRange = NativeByteRange(0, 0),
            contentRange = NativeByteRange(fileOffset, bytes.size),
        )

    @Test
    fun `extracts build-id from named section little-endian`() {
        val buildId = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14)
        val noteBytes = gnuBuildIdNoteBytes(buildId)
        val buf = noteBytes.toCommonByteBuffer()
        val section = makeSection(".note.gnu.build-id", noteBytes)
        val header = makeHeader(NativeBinaryEndian.Little)
        val result = ElfParser.parseGnuBuildId(buf, listOf(section), header)
        assertEquals("0102030405060708090a0b0c0d0e0f1011121314", result)
    }

    @Test
    fun `extracts build-id from unnamed SHT_NOTE section when named section absent`() {
        val buildId = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        val noteBytes = gnuBuildIdNoteBytes(buildId)
        val buf = noteBytes.toCommonByteBuffer()
        val section = makeSection(".note.generic", noteBytes)
        val header = makeHeader()
        val result = ElfParser.parseGnuBuildId(buf, listOf(section), header)
        assertEquals("deadbeef", result)
    }

    @Test
    fun `returns null when no SHT_NOTE sections present`() {
        val header = makeHeader()
        val result = ElfParser.parseGnuBuildId(ByteArray(0).toCommonByteBuffer(), emptyList(), header)
        assertNull(result)
    }

    @Test
    fun `returns null when note section has no GNU build-id entry`() {
        // A note entry with name "LNX\0" (not GNU) and type 1
        val noteBytes = ByteArray(28)
        noteBytes.putU32Le(0, 4u)  // namesz
        noteBytes.putU32Le(4, 8u)  // descsz
        noteBytes.putU32Le(8, 1u)  // type (not 3)
        noteBytes[12] = 'L'.code.toByte()
        noteBytes[13] = 'N'.code.toByte()
        noteBytes[14] = 'X'.code.toByte()
        noteBytes[15] = 0
        // desc bytes (8 bytes)
        val buf = noteBytes.toCommonByteBuffer()
        val section = makeSection(".note.linux", noteBytes)
        assertNull(ElfParser.parseGnuBuildId(buf, listOf(section), makeHeader()))
    }

    @Test
    fun `returns null when section contentRange is null`() {
        val section = ElfSectionHeader(
            index = 0, nameOffset = 0u, name = ".note.gnu.build-id",
            type = ElfSectionType.Note, flags = 0uL, address = 0uL,
            fileOffset = 0uL, size = 20uL, link = 0u, info = 0u,
            addressAlignment = 0uL, entrySize = 0uL,
            headerRange = NativeByteRange(0, 0),
            contentRange = null,
        )
        assertNull(ElfParser.parseGnuBuildId(ByteArray(20).toCommonByteBuffer(), listOf(section), makeHeader()))
    }

    @Test
    fun `prefers named section over unnamed fallback`() {
        // Named section has a real build-id, unnamed has a different one
        val realBuildId = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val decoyBuildId = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val realNote = gnuBuildIdNoteBytes(realBuildId)
        val decoyNote = gnuBuildIdNoteBytes(decoyBuildId)

        // Put named section at offset 0, unnamed section at offset realNote.size
        val combined = realNote + decoyNote
        val buf = combined.toCommonByteBuffer()

        val named = makeSection(".note.gnu.build-id", realNote, fileOffset = 0)
        val unnamed = makeSection(".note.other", decoyNote, fileOffset = realNote.size)

        val result = ElfParser.parseGnuBuildId(buf, listOf(unnamed, named), makeHeader())
        assertEquals("aabbccdd", result)
    }

    @Test
    fun `extracts build-id big-endian`() {
        val buildId = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        val noteBytes = gnuBuildIdNoteBytes(buildId, bigEndian = true)
        val buf = noteBytes.toCommonByteBuffer()
        val section = makeSection(".note.gnu.build-id", noteBytes)
        val header = makeHeader(NativeBinaryEndian.Big)
        assertEquals("cafe", ElfParser.parseGnuBuildId(buf, listOf(section), header))
    }
}
