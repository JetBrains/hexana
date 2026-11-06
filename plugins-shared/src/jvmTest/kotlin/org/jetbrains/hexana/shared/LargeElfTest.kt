package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.MappedFileBytes
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An ELF64 file of 3 GiB whose `.text` sits at 2.5 GiB and whose section header table and section
 * name table sit past 3 GiB — the layout of a large debug build, where the tables a parser needs
 * are at the end. Sparse, so nothing big is written.
 */
class LargeElfTest {
    private val dir: Path = Files.createTempDirectory("hexana-large-elf")
    private val size = 3L * 1024 * 1024 * 1024 + 0x1000
    private val textOffset = 5L * 512 * 1024 * 1024
    private val sectionTableOffset = 3L * 1024 * 1024 * 1024
    private val namesOffset = sectionTableOffset + 0x100
    private val names = "\u0000.text\u0000.shstrtab\u0000.gnu_debuglink\u0000".encodeToByteArray()
    private val debugLinkOffset = 11L * 256 * 1024 * 1024 // 2.75 GiB
    private val debugLink = "big.debug\u0000\u0000\u0000".encodeToByteArray() + ByteArray(4)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun le(capacity: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun header() = le(64) {
        put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
        position(0x10); putShort(2); putShort(0x3E); putInt(1)
        putLong(0x400000) // entry
        putLong(0) // phoff
        putLong(sectionTableOffset)
        putInt(0) // flags
        putShort(64); putShort(56); putShort(0) // ehsize, phentsize, phnum
        putShort(64); putShort(4); putShort(2) // shentsize, shnum, shstrndx
    }

    private fun section(name: Int, type: Int, flags: Long, address: Long, offset: Long, length: Long) = le(64) {
        putInt(name); putInt(type); putLong(flags); putLong(address); putLong(offset); putLong(length)
        putInt(0); putInt(0); putLong(1); putLong(0)
    }

    private fun largeElf(): MappedFileBytes {
        val file = dir.resolve("big.elf")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.setLength(size)
            raf.write(header())
            raf.seek(textOffset); raf.write(ByteArray(16) { 0x90.toByte() })
            raf.seek(sectionTableOffset)
            raf.write(ByteArray(64))
            raf.write(section(name = 1, type = 1, flags = 6, address = 0x400000, offset = textOffset, length = 16))
            raf.write(section(name = 7, type = 3, flags = 0, address = 0, offset = namesOffset, length = names.size.toLong()))
            raf.write(section(name = 17, type = 1, flags = 0, address = 0, offset = debugLinkOffset, length = debugLink.size.toLong()))
            raf.seek(namesOffset); raf.write(names)
            raf.seek(debugLinkOffset); raf.write(debugLink)
        }
        return MappedFileBytes.open(file)
    }

    @Test
    fun `sections past 2 GiB are found when the provider gets the whole file`() {
        val source = largeElf()
        val head = source.window(0, 64 * 1024)
        val provider = assertIs<ElfBinaryProvider>(createBinaryProvider(head, "big.elf", source = source))

        assertEquals(size, provider.fileSize)
        val sections = assertNotNull(provider.sections)
        assertEquals(listOf("", ".text", ".shstrtab", ".gnu_debuglink"), sections.map { it.name })
        val text = sections[1].contentRange!!
        assertEquals(textOffset, text.start)
        assertEquals(16L, text.length)
        assertTrue(provider.structureRanges.any { it.label == ".text" && it.offset == textOffset })
        assertNull(provider.sizeAnalysis, "an Int size tree cannot describe a 3 GiB file")
        assertEquals(emptyList(), provider.parseDiagnostics.filter { it.severity.name == "Error" })

        // The debug link sits past 2 GiB too, as it does at the end of a large binary.
        assertEquals("big.debug", NativeDebugCompanionScanner.scan(provider).gnuDebugLink)

        // The Sections tab selects the section where it is, past 2 GiB.
        val tab = NativeTableTabsProvider().tableTabs(provider).single { it.name == "Sections" }
        val row = (0 until tab.rowCount).single { tab.columns.first { c -> c.name == "Name" }.cellProvider(it).representation == ".text" }
        val selected = assertNotNull(tab.rowSelectionProvider(row))
        assertEquals(textOffset to 16L, selected.offset to selected.size)
    }

    @Test
    fun `the head alone cannot reach the section table`() {
        val source = largeElf()
        val provider = assertIs<ElfBinaryProvider>(createBinaryProvider(source.window(0, 64 * 1024), "big.elf"))
        assertNull(provider.sections)
    }
}
