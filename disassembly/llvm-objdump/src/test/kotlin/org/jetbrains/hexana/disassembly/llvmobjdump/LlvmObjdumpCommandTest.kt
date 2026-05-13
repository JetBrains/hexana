package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LlvmObjdumpCommandTest {
    @Test
    fun `builds headers command`() {
        val command = LlvmObjdumpCommands.headers(Path.of("/tmp/app"))

        assertEquals(LlvmObjdumpCommandMode.Headers, command.mode)
        assertEquals(listOf("--file-headers", "--section-headers", "/tmp/app"), command.arguments)
    }

    @Test
    fun `builds symbols command`() {
        val command = LlvmObjdumpCommands.symbols(Path.of("/tmp/app"))

        assertEquals(LlvmObjdumpCommandMode.Symbols, command.mode)
        assertEquals(listOf("--syms", "/tmp/app"), command.arguments)
    }

    @Test
    fun `builds relocation commands`() {
        val file = Path.of("/tmp/app")

        assertEquals(listOf("--reloc", "/tmp/app"), LlvmObjdumpCommands.relocations(file).arguments)
        assertEquals(listOf("--dynamic-reloc", "/tmp/app"), LlvmObjdumpCommands.dynamicRelocations(file).arguments)
    }

    @Test
    fun `builds disassembly commands`() {
        val file = Path.of("/tmp/app")

        assertEquals(
            listOf("--disassemble", "/tmp/app"),
            LlvmObjdumpCommands.disassembleExecutableSections(file).arguments,
        )
        assertEquals(
            listOf("--disassemble", "--disassemble-symbols=main", "/tmp/app"),
            LlvmObjdumpCommands.disassembleSymbol(file, "main").arguments,
        )
    }

    @Test
    fun `builds disassembly commands with syntax mode`() {
        val file = Path.of("/tmp/app")

        assertEquals(
            listOf("--disassemble", "--x86-asm-syntax=intel", "/tmp/app"),
            LlvmObjdumpCommands.disassembleExecutableSections(file, LlvmObjdumpSyntaxMode.Intel).arguments,
        )
        assertEquals(
            listOf("--disassemble", "--x86-asm-syntax=att", "--disassemble-symbols=main", "/tmp/app"),
            LlvmObjdumpCommands.disassembleSymbol(file, "main", LlvmObjdumpSyntaxMode.Att).arguments,
        )
    }

    @Test
    fun `rejects blank disassembly symbol`() {
        assertThrows(IllegalArgumentException::class.java) {
            LlvmObjdumpCommands.disassembleSymbol(Path.of("/tmp/app"), " ")
        }
    }
}
