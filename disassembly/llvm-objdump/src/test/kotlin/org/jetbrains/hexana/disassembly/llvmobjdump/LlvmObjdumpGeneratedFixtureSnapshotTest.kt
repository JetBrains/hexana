package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import kotlin.io.path.readText
import org.jetbrains.hexana.disassembly.core.NativeDisassemblyConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlvmObjdumpGeneratedFixtureSnapshotTest {
    @Test
    fun `parses generated ELF executable disassembly snapshot`() {
        val parsed = parseFixture("elf/sample.elf.disassemble.txt")

        assertTrue(parsed.functions.any { it.name == "hexana_add" })
        assertTrue(parsed.functions.any { it.name == "hexana_dispatch" })
        assertTrue(parsed.functions.any { it.name == "main" })
        assertTrue(parsed.instructions.size > 50)
        assertTrue(parsed.instructions.any { it.sectionName == ".text" && it.bytes.isNotEmpty() })

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(parsed)
        assertEquals(NativeDisassemblyConfidence.BackendProvided, native.confidence)
        assertTrue(native.functions.first { it.name == "hexana_dispatch" }.basicBlocks.size > 1)
        assertTrue(native.instructions.any { instruction -> instruction.xrefs.isNotEmpty() })
    }

    @Test
    fun `parses generated ELF object disassembly snapshot`() {
        val parsed = parseFixture("elf/sample.o.disassemble.txt")

        assertTrue(parsed.functions.any { it.name == "hexana_add" })
        assertTrue(parsed.functions.any { it.name == "main" })
        assertTrue(parsed.instructions.any { it.address == 0xa0uL && it.functionName == "main" })
    }

    @Test
    fun `parses generated Mach-O object disassembly snapshot`() {
        val parsed = parseFixture("macho/sample-x86_64.o.disassemble.txt")

        assertTrue(parsed.functions.any { it.name == "_hexana_add" })
        assertTrue(parsed.functions.any { it.name == "_main" })
        assertTrue(parsed.instructions.any { it.sectionName == "__TEXT,__text" && it.bytes.isNotEmpty() })
    }

    @Test
    fun `parses generated COFF object disassembly snapshot`() {
        val parsed = parseFixture("pe/sample.coff.disassemble.txt")

        assertTrue(parsed.functions.any { it.name == "hexana_add" })
        assertTrue(parsed.functions.any { it.name == "mainCRTStartup" })
        assertTrue(parsed.instructions.any { it.sectionName == ".text" && it.bytes.isNotEmpty() })
    }

    private fun parseFixture(relativePath: String): LlvmObjdumpDisassembly {
        val text = fixtureRoot()
            .resolve("llvm-objdump")
            .resolve(relativePath)
            .readText()
        val parsed = LlvmObjdumpDisassemblyParser.parse(text)
        assertTrue("Expected instructions in $relativePath", parsed.instructions.isNotEmpty())
        return parsed
    }
}

private fun fixtureRoot(): Path {
    var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (current != null) {
        val candidate = current.resolve("disassembly/native-fixtures/test-data")
        if (candidate.toFile().isDirectory) return candidate
        current = current.parent
    }
    error("Cannot find disassembly/native-fixtures/test-data from ${System.getProperty("user.dir")}")
}
