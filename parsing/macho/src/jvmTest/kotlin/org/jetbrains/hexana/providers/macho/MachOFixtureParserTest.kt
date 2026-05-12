package org.jetbrains.hexana.providers.macho

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

class MachOFixtureParserTest {
    @Test
    fun `parses generated Mach-O x86_64 object fixture`() {
        val result = assertIs<NativeParseSuccess<MachOFile>>(MachOParser.parse(fixtureBytes("macho/sample-x86_64.o").toCommonByteBuffer()))
        val file = assertIs<MachOThinFile>(result.value)

        assertEquals(NativeBinaryWordSize.Bits64, file.header.wordSize)
        assertEquals(0x01000007, file.header.cpuType)
        assertEquals(1u, file.header.fileType)
        assertEquals(4u, file.header.loadCommandCount)
        assertEquals(emptyList(), result.diagnostics)

        assertContainsNames(file.segments.flatMap { it.sections }.map { it.name }, "__text", "__data", "__debug_info", "__debug_line")
        assertContainsNames(file.symbols.map { it.name }, "_hexana_add", "_hexana_dispatch", "_hexana_counter", "_hexana_message", "_main")
        assertTrue(file.relocations.any { it.symbol?.name == "_hexana_add" })
        assertTrue(file.relocations.any { it.symbol?.name == "_hexana_dispatch" })
        assertReferenceContains("macho/sample-x86_64.o.llvm-readobj.txt", "Format: Mach-O 64-bit x86-64", "NumOfLoadCommands: 4", "Name: _hexana_add")
    }

    @Test
    fun `parses generated Mach-O arm64 object fixture`() {
        val result = assertIs<NativeParseSuccess<MachOFile>>(MachOParser.parse(fixtureBytes("macho/sample-arm64.o").toCommonByteBuffer()))
        val file = assertIs<MachOThinFile>(result.value)

        assertEquals(NativeBinaryWordSize.Bits64, file.header.wordSize)
        assertEquals(0x0100000C, file.header.cpuType)
        assertEquals(1u, file.header.fileType)
        assertEquals(emptyList(), result.diagnostics)

        assertContainsNames(file.segments.flatMap { it.sections }.map { it.name }, "__text", "__data", "__debug_info", "__debug_line")
        assertContainsNames(file.symbols.map { it.name }, "_hexana_add", "_hexana_dispatch", "_hexana_counter", "_hexana_message", "_main")
        assertReferenceContains("macho/sample-arm64.o.llvm-readobj.txt", "Format: Mach-O arm64", "NumOfLoadCommands: 4", "Name: _hexana_add")
    }

    @Test
    fun `reports failure for truncated generated Mach-O fixture`() {
        val truncated = fixtureBytes("macho/sample-x86_64.o").copyOf(24)

        val result = assertIs<NativeParseFailure>(MachOParser.parse(truncated.toCommonByteBuffer()))

        assertEquals("macho.invalid_header", result.diagnostics.single().code)
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
