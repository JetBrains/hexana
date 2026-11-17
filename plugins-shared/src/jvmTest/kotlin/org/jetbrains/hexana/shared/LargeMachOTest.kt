package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.MappedFileBytes
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import org.jetbrains.hexana.providers.macho.MachOParser
import org.jetbrains.hexana.providers.wholeFile
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mach-O files past 2 GiB, written sparse by hand so the test stores almost nothing: a thin 64-bit
 * binary whose `__TEXT` segment sits at 2.5 GiB and whose symbol, string and relocation tables sit
 * past 3 GiB (the layout of a large debug build), and a universal binary whose second slice sits at
 * 2.5 GiB, placed there by a `fat_arch_64`.
 */
class LargeMachOTest {
    private val dir: Path = Files.createTempDirectory("hexana-large-macho")
    private val size = 3L * 1024 * 1024 * 1024 + 0x1000
    private val farOffset = 5L * 512 * 1024 * 1024 // 2.5 GiB
    private val symbolOffset = 3L * 1024 * 1024 * 1024
    private val stringOffset = symbolOffset + 0x100
    private val relocationOffset = symbolOffset + 0x200
    private val strings = "\u0000_main\u0000".encodeToByteArray()
    private val textAddress = 0x100000000L

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun buffer(capacity: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(capacity).order(order).apply(fill).array()

    private fun ByteBuffer.name16(name: String) {
        val bytes = name.encodeToByteArray()
        put(bytes); put(ByteArray(16 - bytes.size))
    }

    private fun thinMachO(): MachOBinaryProvider {
        val segmentCommandSize = 72 + 80
        val commands = buffer(segmentCommandSize + 24) {
            // LC_SEGMENT_64 with one section, both at 2.5 GiB.
            putInt(0x19); putInt(segmentCommandSize); name16("__TEXT")
            putLong(textAddress); putLong(0x1000); putLong(farOffset); putLong(0x1000)
            putInt(5); putInt(5); putInt(1); putInt(0)
            name16("__text"); name16("__TEXT")
            putLong(textAddress); putLong(16); putInt(farOffset.toInt()); putInt(4)
            putInt(relocationOffset.toInt()); putInt(1) // reloff, nreloc (u32 offsets past 2 GiB)
            putInt(0x80000400.toInt()); putInt(0); putInt(0); putInt(0)
            // LC_SYMTAB: one symbol and its string table, past 3 GiB.
            putInt(0x2); putInt(24); putInt(symbolOffset.toInt()); putInt(1); putInt(stringOffset.toInt()); putInt(strings.size)
        }
        val header = buffer(32) {
            putInt(0xFEEDFACF.toInt()); putInt(0x01000007); putInt(3); putInt(2) // x86_64 MH_EXECUTE
            putInt(2); putInt(commands.size); putInt(0); putInt(0)
        }
        val symbol = buffer(16) {
            putInt(1); put(0x0F); put(1); putShort(0); putLong(textAddress) // _main, N_SECT | N_EXT, section 1
        }
        // An external, pc-relative, 4-byte relocation against symbol 0.
        val relocation = buffer(8) { putInt(0); putInt((1 shl 24) or (2 shl 25) or (1 shl 27) or (2 shl 28)) }

        val file = dir.resolve("big.macho")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.setLength(size)
            raf.write(header); raf.write(commands)
            raf.seek(farOffset); raf.write(ByteArray(16) { 0x90.toByte() })
            raf.seek(symbolOffset); raf.write(symbol)
            raf.seek(stringOffset); raf.write(strings)
            raf.seek(relocationOffset); raf.write(relocation)
        }
        val source = MappedFileBytes.open(file)
        return assertIs<MachOBinaryProvider>(createBinaryProvider(source.window(0, 64 * 1024), "big.macho", source = source))
    }

    @Test
    fun `segments, symbols and relocations past 2 GiB are found when the provider gets the whole file`() {
        val provider = thinMachO()

        assertEquals(size, provider.fileSize)
        assertSame(provider.source, provider.wholeFile)
        val segment = assertNotNull(provider.segments).single()
        assertEquals(farOffset, segment.fileRange!!.start)
        val text = assertNotNull(provider.sections).single()
        assertEquals(farOffset to 16L, text.contentRange!!.start to text.contentRange!!.length)

        val symtab = assertNotNull(provider.symtab)
        assertEquals(symbolOffset, symtab.symbolTableRange!!.start)
        assertEquals(stringOffset, symtab.stringTableRange!!.start)
        val symbol = assertNotNull(provider.symbols).single()
        assertEquals("_main", symbol.name)
        assertEquals(symbolOffset, symbol.recordRange.start)

        val relocation = assertNotNull(provider.relocations).single()
        assertEquals(relocationOffset, relocation.recordRange.start)
        assertEquals("_main", relocation.symbol?.name)

        val function = assertNotNull(provider.nativeFunctions).single { it.name == "_main" }
        assertEquals(farOffset, function.fileOffset)
        assertTrue(provider.structureRanges.any { it.label == "__TEXT" && it.offset == farOffset })
        assertNull(provider.sizeAnalysis, "an Int size tree cannot describe a 3 GiB file")
        assertEquals(emptyList(), provider.parseDiagnostics.filter { it.severity.name != "Info" })

        // The tabs select the records where they are, past 2 GiB.
        val tabs = NativeTableTabsProvider().tableTabs(provider)
        val sectionsTab = tabs.single { it.name == "Sections" }
        val selectedSection = assertNotNull(sectionsTab.rowSelectionProvider(0))
        assertEquals(farOffset to 16L, selectedSection.offset to selectedSection.size)
        val symbolsTab = tabs.single { it.name == "Symbols" }
        assertEquals(symbolOffset, assertNotNull(symbolsTab.rowSelectionProvider(0)).offset)
    }

    @Test
    fun `the head alone cannot reach the tables past it`() {
        val source = thinMachO().source
        val provider = assertIs<MachOBinaryProvider>(createBinaryProvider(source.window(0, 64 * 1024), "big.macho"))
        assertNull(provider.sections!!.single().contentRange)
        assertNull(provider.symbols)
    }

    private fun fatMachO(): FatMachOBinaryProvider {
        val nearOffset = 0x1000L
        val sliceSize = 0x1000L
        val fatHeader = buffer(8 + 2 * 32, ByteOrder.BIG_ENDIAN) {
            putInt(0xCAFEBABF.toInt()); putInt(2) // FAT_MAGIC_64
            putInt(0x01000012); putInt(0); putLong(nearOffset); putLong(sliceSize); putInt(12); putInt(0) // ppc64
            putInt(0x0100000C); putInt(0); putLong(farOffset); putLong(sliceSize); putInt(14); putInt(0) // arm64
        }
        // Slice headers with no load commands: a big-endian ppc64 one and a little-endian arm64 one.
        val ppc64 = buffer(32, ByteOrder.BIG_ENDIAN) { putInt(0xFEEDFACF.toInt()); putInt(0x01000012); putInt(0); putInt(2) }
        val arm64 = buffer(32) { putInt(0xFEEDFACF.toInt()); putInt(0x0100000C); putInt(0); putInt(2) }

        val file = dir.resolve("big-universal")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.setLength(farOffset + sliceSize)
            raf.write(fatHeader)
            raf.seek(nearOffset); raf.write(ppc64)
            raf.seek(farOffset); raf.write(arm64)
        }
        val source = MappedFileBytes.open(file)
        return assertIs<FatMachOBinaryProvider>(createBinaryProvider(source.window(0, 64 * 1024), "big-universal", source = source))
    }

    @Test
    fun `a universal slice past 2 GiB is listed and its header read from the whole file`() {
        val provider = fatMachO()

        val architectures = assertNotNull(provider.architectures)
        assertEquals(listOf(0x1000uL, farOffset.toULong()), architectures.map { it.offset })
        val far = architectures[1]
        assertEquals(farOffset, far.sliceRange!!.start)

        val header = assertNotNull(provider.sliceHeader(far))
        assertEquals(0x0100000C, header.cpuType)
        assertEquals(NativeBinaryEndian.Little, header.endian)
        assertEquals(NativeBinaryEndian.Big, provider.sliceHeader(architectures[0])?.endian)
        val slice = assertNotNull(provider.sliceBytes(far))
        assertEquals(0x1000, slice.size)
        assertEquals(0x0100000C, MachOParser.parseHeader(slice)?.cpuType)

        assertTrue(provider.structureRanges.any { it.label == "Slice 1" && it.offset == farOffset })
        assertNull(provider.sizeAnalysis)

        val tab = NativeTableTabsProvider().tableTabs(provider).single { it.name == "Universal" }
        val endianness = tab.columns.single { it.name == "Endianness" }
        assertEquals(listOf("big", "little"), (0 until tab.rowCount).map { endianness.cellProvider(it).representation })
        assertEquals(farOffset, assertNotNull(tab.rowSelectionProvider(1)).offset)
    }

    @Test
    fun `the head alone cannot place a slice past it`() {
        val source = fatMachO().source
        val provider = assertIs<FatMachOBinaryProvider>(createBinaryProvider(source.window(0, 64 * 1024), "big-universal"))
        assertNull(provider.architectures)
    }
}
