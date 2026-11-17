package org.jetbrains.hexana.providers.elf

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

class ElfFixtureParserTest {
    @Test
    fun `parses generated ELF object fixture`() {
        val result = assertIs<NativeParseSuccess<ElfFile>>(ElfParser.parse(fixtureBytes("elf/sample.o").toCommonByteBuffer()))
        val file = result.value

        assertEquals(NativeBinaryWordSize.Bits64, file.header.wordSize)
        assertEquals(1, file.header.fileType)
        assertEquals(0x3E, file.header.machine)
        assertEquals(24, file.sections.size)
        assertEquals(0, file.programHeaders.size)
        assertEquals(emptyList(), result.diagnostics)

        assertContainsNames(file.sections.map { it.name }, ".text", ".data", ".rodata", ".debug_info", ".symtab", ".rela.text")
        assertContainsNames(file.symbols.map { it.name }, "hexana_add", "hexana_dispatch", "hexana_counter", "hexana_message", "main")
        assertTrue(file.relocations.any { it.symbol?.name == "hexana_add" })
        assertTrue(file.relocations.any { it.symbol?.name == "hexana_dispatch" })
        assertReferenceContains("elf/sample.o.llvm-readobj.txt", "Format: elf64-x86-64", "SectionHeaderCount: 24", "Name: hexana_add")
    }

    @Test
    fun `parses generated ELF executable fixture`() {
        val result = assertIs<NativeParseSuccess<ElfFile>>(ElfParser.parse(fixtureBytes("elf/sample.elf").toCommonByteBuffer()))
        val file = result.value

        assertEquals(NativeBinaryWordSize.Bits64, file.header.wordSize)
        assertEquals(2, file.header.fileType)
        assertEquals(0x3E, file.header.machine)
        assertTrue(file.programHeaders.isNotEmpty())
        assertEquals(emptyList(), result.diagnostics)

        assertContainsNames(file.sections.map { it.name }, ".text", ".data", ".rodata", ".debug_info", ".symtab")
        assertContainsNames(file.symbols.map { it.name }, "hexana_add", "hexana_dispatch", "hexana_counter", "hexana_message", "main")
        assertReferenceContains("elf/sample.elf.llvm-readobj.txt", "Type: Executable", "Name: .text", "Name: main")
    }

    @Test
    fun `reports failure for truncated generated ELF fixture`() {
        val truncated = fixtureBytes("elf/sample.o").copyOf(24)

        val result = assertIs<NativeParseFailure>(ElfParser.parse(truncated.toCommonByteBuffer()))

        assertEquals("elf.invalid_header", result.diagnostics.single().code)
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
