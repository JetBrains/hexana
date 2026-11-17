package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeParseFailure
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeFixtureParserTest {
    @Test
    fun `parses generated COFF object fixture`() {
        val result = assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(fixtureBytes("pe/sample.coff").toCommonByteBuffer()))
        val file = assertIs<CoffObjectFile>(result.value)

        assertEquals(0x8664, file.coffHeader.machine)
        assertEquals(10, file.coffHeader.sectionCount)
        assertEquals(30u, file.coffHeader.symbolCount)
        assertEquals(0, file.coffHeader.optionalHeaderSize)
        assertEquals(emptyList(), result.diagnostics)

        assertContainsNames(file.sections.map { it.name }, ".text", ".data", ".rdata", ".debug\$S", ".debug\$T", ".llvm_addrsig")
        assertContainsNames(file.coffSymbols.map { it.name }, "hexana_add", "hexana_dispatch", "hexana_counter", "hexana_message", "mainCRTStartup")
        assertTrue(file.relocations.any { it.symbol?.name == "hexana_add" })
        assertTrue(file.relocations.any { it.symbol?.name == "hexana_dispatch" })
        assertReferenceContains("pe/sample.coff.llvm-readobj.txt", "Format: COFF-x86-64", "SectionCount: 10", "Name: hexana_add")
    }

    @Test
    fun `parses generated PE executable fixture`() {
        val result = assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(fixtureBytes("pe/sample.exe").toCommonByteBuffer()))
        val file = assertIs<PeImageFile>(result.value)

        assertEquals(0x8664, file.coffHeader.machine)
        assertEquals(5, file.sections.size)
        assertEquals(NativeBinaryWordSize.Bits64, file.optionalHeader.wordSize)
        assertEquals(0u, file.optionalHeader.dataDirectories[1].rva)
        assertEquals(emptyList(), file.imports)
        assertEquals(emptyList(), file.coffSymbols)
        assertEquals(emptyList(), result.diagnostics)

        assertContainsNames(file.sections.map { it.name }, ".text", ".rdata", ".data", ".pdata", ".reloc")
        assertEquals("sample.exe", file.exports?.name)
        assertTrue(file.debugEntries.isNotEmpty())
        assertReferenceContains("pe/sample.exe.llvm-readobj.txt", "Format: COFF-x86-64", "SectionCount: 5", "ExportTableRVA")
    }

    @Test
    fun `parses generated COFF archive fixture`() {
        val result = assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(fixtureBytes("pe/sample.lib").toCommonByteBuffer()))
        val file = assertIs<PeArchiveFile>(result.value)

        assertEquals(6, file.members.size)
        assertEquals("/", file.members.first().name)
        assertTrue(file.members.drop(1).all { it.name == "sample.exe" })
        assertTrue(file.members.all { it.contentRange.size > 0 })
        assertEquals(emptyList(), result.diagnostics)
    }

    @Test
    fun `reports failure for truncated generated COFF fixture`() {
        val truncated = fixtureBytes("pe/sample.coff").copyOf(20)

        val result = assertIs<NativeParseFailure>(PeParser.parse(truncated.toCommonByteBuffer()))

        assertEquals("pe.invalid_header", result.diagnostics.single().code)
    }

    private fun assertContainsNames(names: List<String>, vararg expected: String) {
        for (name in expected) {
            assertTrue(name in names, "Expected $name in $names")
        }
    }

    private fun assertReferenceContains(relativePath: String, vararg expected: String) {
        val text = fixtureText(relativePath)
        for (value in expected) {
            assertTrue(value in text, "Expected reference dump to contain $value")
        }
    }
}

private fun fixtureBytes(relativePath: String): ByteArray =
    fixtureRoot().resolve(relativePath).readBytes()

private fun fixtureText(relativePath: String): String =
    fixtureRoot().resolve(relativePath).readText()

private fun fixtureRoot(): Path {
    var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (current != null) {
        val candidate = current.resolve("parsing/native-fixtures/test-data")
        if (candidate.toFile().isDirectory) return candidate
        current = current.parent
    }
    error("Cannot find parsing/native-fixtures/test-data from ${System.getProperty("user.dir")}")
}
