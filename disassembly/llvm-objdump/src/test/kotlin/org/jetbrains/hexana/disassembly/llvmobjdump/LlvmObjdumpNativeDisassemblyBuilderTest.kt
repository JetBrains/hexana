package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import org.jetbrains.hexana.disassembly.core.DisassemblySourceLocation
import org.jetbrains.hexana.disassembly.core.NativeDisassemblyConfidence
import org.jetbrains.hexana.disassembly.core.NativeRelocationAnnotation
import org.jetbrains.hexana.disassembly.core.NativeXrefKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LlvmObjdumpNativeDisassemblyBuilderTest {
    @Test
    fun `returns unknown confidence for empty disassembly`() {
        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            LlvmObjdumpDisassembly(functions = emptyList()),
        )

        assertEquals(NativeDisassemblyConfidence.Unknown, native.confidence)
        assertEquals(emptyList<Any>(), native.functions)
        assertEquals(emptyList<Any>(), native.instructions)
    }

    @Test
    fun `converts llvm objdump disassembly into native model`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(readSnapshot("disassemble-x86_64.txt"))

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            mapVirtualAddressToFileOffset = { address ->
                (address - 0x401000uL + 0x1000uL).toLong()
            },
            findSectionName = { ".text" },
            findRelocation = { address, size, sectionName ->
                if (address == 0x401140uL && size == 1 && sectionName == ".text") {
                    NativeRelocationAnnotation(type = "R_X86_64_NONE", symbolName = "main")
                } else {
                    null
                }
            },
            findSourceLocation = { address ->
                if (address == 0x40114buL) {
                    DisassemblySourceLocation(filePath = "/tmp/main.c", line = 7, column = 3)
                } else {
                    null
                }
            },
        )

        assertEquals(NativeDisassemblyConfidence.BackendProvided, native.confidence)
        assertEquals(2, native.functions.size)
        assertEquals(9, native.instructions.size)

        val main = native.functions[0]
        assertEquals("main", main.name)
        assertEquals(0x401140uL, main.entry.virtualAddress)
        assertEquals(0x1140L, main.entry.fileOffset)
        assertEquals(NativeDisassemblyConfidence.BackendProvided, main.confidence)
        assertEquals(1, main.basicBlocks.size)
        assertEquals(0x401140uL, main.basicBlocks[0].start.virtualAddress)
        assertEquals(0x401153uL, main.basicBlocks[0].endInclusive.virtualAddress)

        val call = main.instructions[3]
        assertEquals(0x40114buL, call.address.virtualAddress)
        assertEquals(0x114bL, call.address.fileOffset)
        assertEquals(".text", call.sectionName)
        assertEquals(listOf(0xe8, 0xe0, 0xfe, 0xff, 0xff), call.bytes)
        assertEquals("callq", call.mnemonic)
        assertEquals("0x401030 <puts@plt>", call.operandsText)
        assertEquals("callq 0x401030 <puts@plt>", call.assemblyText)
        assertEquals("main", call.symbol)
        assertEquals(DisassemblySourceLocation(filePath = "/tmp/main.c", line = 7, column = 3), call.sourceLocation)
        assertEquals(null, call.relocation)
        assertEquals(1, call.xrefs.size)
        assertEquals(NativeXrefKind.Call, call.xrefs[0].kind)
        assertEquals(0x401030uL, call.xrefs[0].target?.virtualAddress)
        assertEquals(0x1030L, call.xrefs[0].target?.fileOffset)
        assertEquals("puts@plt", call.xrefs[0].symbolName)
        assertEquals(NativeRelocationAnnotation(type = "R_X86_64_NONE", symbolName = "main"), main.instructions[0].relocation)
    }

    @Test
    fun `keeps file offset unknown when mapper cannot resolve address`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(readSnapshot("disassemble-x86_64.txt"))

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        assertEquals(null, native.functions[0].entry.fileOffset)
        assertEquals(null, native.functions[0].instructions[0].address.fileOffset)
        assertEquals(".text", native.functions[0].instructions[0].sectionName)
        assertEquals(null, native.functions[0].instructions[0].relocation)
    }

    @Test
    fun `maps section relative object disassembly addresses`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            Disassembly of section .text:

            0000000000000000 <main>:
                   0: 55 pushq %rbp
                   1: e8 00 00 00 00 callq 0x6 <main+0x6>
                   6: c3 retq
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            mapSectionAddressToFileOffset = { address, sectionName ->
                if (sectionName == ".text" && address < 0x20uL) {
                    (0x180uL + address).toLong()
                } else {
                    null
                }
            },
        )

        val main = native.functions.single()
        assertEquals(0x180L, main.entry.fileOffset)
        assertEquals(".text", main.instructions[0].sectionName)
        assertEquals(0x181L, main.instructions[1].address.fileOffset)
        assertEquals(0x186L, main.instructions[1].xrefs.single().target?.fileOffset)
    }

    @Test
    fun `does not create xrefs for non control-flow instructions with address operands`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <loads>:
                1000: 48 8d 3d 00 20 00 00 leaq 0x2000(%rip), %rdi
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        assertEquals(emptyList<Any>(), native.instructions.single().xrefs)
    }

    @Test
    fun `does not create direct xrefs for indirect control-flow operands`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <indirects>:
                1000: ff 15 2a 00 00 00 callq *0x2a(%rip)
                1006: ff 60 40 jmpq *0x40(%rax)
                1009: ff 25 00 20 00 00 jmp qword ptr [rip + 0x2000]
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        assertEquals(listOf(emptyList<Any>(), emptyList<Any>(), emptyList<Any>()), native.instructions.map { it.xrefs })
        assertEquals(2, native.functions.single().basicBlocks.size)
        assertEquals(emptyList<Any>(), native.functions.single().basicBlocks.flatMap { it.outgoingXrefs })
    }

    @Test
    fun `creates xrefs for direct AT&T immediate targets`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <branchy>:
                1000: e9 00 00 00 00 jmp ${'$'}0x1008 <branchy+0x8>
                1005: 90 nop
                1008: c3 retq
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        val xref = native.instructions.first().xrefs.single()
        assertEquals(NativeXrefKind.Jump, xref.kind)
        assertEquals(0x1008uL, xref.target?.virtualAddress)
        assertEquals("branchy+0x8", xref.symbolName)
    }

    @Test
    fun `keeps direct xrefs when demangled symbol contains parentheses`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <caller>:
                1000: e8 00 00 00 00 callq 0x2000 <foo(int)>
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        val xref = native.instructions.single().xrefs.single()
        assertEquals(NativeXrefKind.Call, xref.kind)
        assertEquals(0x2000uL, xref.target?.virtualAddress)
        assertEquals("foo(int)", xref.symbolName)
    }

    @Test
    fun `creates xrefs for AArch64 direct branch targets`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <branchy>:
                1000: 54000040 b.eq 0x1008 <branchy+0x8>
                1004: 14000002 b 0x100c <branchy+0xc>
                1008: d65f03c0 ret
                100c: d65f03c0 ret
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        assertEquals(0x1008uL, native.instructions[0].xrefs.single().target?.virtualAddress)
        assertEquals(0x100cuL, native.instructions[1].xrefs.single().target?.virtualAddress)
        assertEquals(4, native.functions.single().basicBlocks.size)
    }

    @Test
    fun `creates xrefs for ARM Thumb conditional branch targets`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <arm_branchy>:
                1000: 1a000002 bne 0x1010 <arm_branchy+0x10>
                1004: eb000001 bl 0x100c <callee>
                1010: e12fff1e bx lr
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        assertEquals(NativeXrefKind.Jump, native.instructions[0].xrefs.single().kind)
        assertEquals(0x1010uL, native.instructions[0].xrefs.single().target?.virtualAddress)
        assertEquals(NativeXrefKind.Call, native.instructions[1].xrefs.single().kind)
        assertEquals(0x100cuL, native.instructions[1].xrefs.single().target?.virtualAddress)
        assertEquals(3, native.functions.single().basicBlocks.size)
    }

    @Test
    fun `creates xrefs for RISC-V direct calls and branches`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <riscv_branchy>:
                1000: 000000ef jal 0x2000 <callee>
                1004: 0000006f jal zero, 0x1010 <riscv_branchy+0x10>
                1008: 00b56a63 bltu a0, a1, 0x1014 <riscv_branchy+0x14>
                1010: 00008067 ret
                1014: 00008067 ret
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)

        assertEquals(NativeXrefKind.Call, native.instructions[0].xrefs.single().kind)
        assertEquals(0x2000uL, native.instructions[0].xrefs.single().target?.virtualAddress)
        assertEquals(NativeXrefKind.Jump, native.instructions[1].xrefs.single().kind)
        assertEquals(0x1010uL, native.instructions[1].xrefs.single().target?.virtualAddress)
        assertEquals(NativeXrefKind.Jump, native.instructions[2].xrefs.single().kind)
        assertEquals(0x1014uL, native.instructions[2].xrefs.single().target?.virtualAddress)
        assertEquals(4, native.functions.single().basicBlocks.size)
    }

    @Test
    fun `passes resolved section name to section address mapper`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
                1000: e9 00 00 00 00 jmp 0x1005
            """.trimIndent(),
        )
        val seenSections = mutableListOf<String?>()

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            findSectionName = { ".text" },
            mapSectionAddressToFileOffset = { address, sectionName ->
                seenSections.add(sectionName)
                (0x400uL + address).toLong()
            },
        )

        assertEquals(listOf(".text", ".text", ".text"), seenSections)
        assertEquals(0x1400L, native.instructions.single().address.fileOffset)
        assertEquals(0x1405L, native.instructions.single().xrefs.single().target?.fileOffset)
    }

    @Test
    fun `maps xref target file offset with target section`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            Disassembly of section .text:

            0000000000001000 <main>:
                1000: e8 fb 0f 00 00 callq 0x2000 <puts@plt>
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            findSectionName = { address ->
                if (address == 0x2000uL) ".plt" else ".text"
            },
            mapSectionAddressToFileOffset = { address, sectionName ->
                when (sectionName) {
                    ".text" -> (0x100uL + (address - 0x1000uL)).toLong()
                    ".plt" -> (0x500uL + (address - 0x2000uL)).toLong()
                    else -> null
                }
            },
        )

        assertEquals(0x500L, native.instructions.single().xrefs.single().target?.fileOffset)
    }

    @Test
    fun `uses relocation symbol instead of placeholder control-flow target`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <object_func>:
                1000: e8 00 00 00 00 callq 0x1005 <object_func+0x5>
                1005: c3 retq
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            findRelocation = { address, size, sectionName ->
                val relocationAddress = 0x1001uL
                if (sectionName == null && relocationAddress >= address && relocationAddress < address + size.toULong()) {
                    NativeRelocationAnnotation(type = "R_X86_64_PLT32", symbolName = "puts")
                } else {
                    null
                }
            },
        )

        val xref = native.instructions.first().xrefs.single()
        assertEquals(NativeXrefKind.Call, xref.kind)
        assertEquals(null, xref.target)
        assertEquals("puts", xref.symbolName)
    }

    @Test
    fun `passes section and instruction range to relocation lookup`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            Disassembly of section .text:

            0000000000001000 <object_func>:
                1000: e8 00 00 00 00 callq 0x1005 <object_func+0x5>
            """.trimIndent(),
        )
        val seenLookups = mutableListOf<Triple<ULong, Int, String?>>()

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            findRelocation = { address, size, sectionName ->
                seenLookups.add(Triple(address, size, sectionName))
                val relocationAddress = 0x1001uL
                if (sectionName == ".text" && relocationAddress >= address && relocationAddress < address + size.toULong()) {
                    NativeRelocationAnnotation(type = "R_X86_64_PLT32", symbolName = "puts")
                } else {
                    null
                }
            },
        )

        assertEquals(listOf(Triple(0x1000uL, 5, ".text")), seenLookups)
        assertEquals(NativeRelocationAnnotation(type = "R_X86_64_PLT32", symbolName = "puts"), native.instructions.single().relocation)
        assertEquals("puts", native.instructions.single().xrefs.single().symbolName)
        assertEquals(null, native.instructions.single().xrefs.single().target)
    }

    @Test
    fun `keeps relocation jump target when parsed target is not a placeholder`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <object_branchy>:
                1000: 74 03 je 0x1005 <.LBB0_1>
                1002: 90 nop
                1003: c3 retq
                1005: c3 retq
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(
            disassembly = llvm,
            findRelocation = { address, size, _ ->
                val relocationAddress = 0x1001uL
                if (relocationAddress >= address && relocationAddress < address + size.toULong()) {
                    NativeRelocationAnnotation(type = "R_X86_64_PC32", symbolName = ".LBB0_1")
                } else {
                    null
                }
            },
        )

        val xref = native.instructions.first().xrefs.single()
        assertEquals(NativeXrefKind.Jump, xref.kind)
        assertEquals(0x1005uL, xref.target?.virtualAddress)
        assertEquals(".LBB0_1", xref.symbolName)
        assertEquals(3, native.functions.single().basicBlocks.size)
    }

    @Test
    fun `splits basic block candidates at jump targets and terminators`() {
        val llvm = LlvmObjdumpDisassemblyParser.parse(
            """
            0000000000001000 <branchy>:
                1000: 74 03 je 0x1005 <branchy+0x5>
                1002: 90 nop
                1003: c3 retq
                1005: c3 retq
            """.trimIndent(),
        )

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(llvm)
        val blocks = native.functions.single().basicBlocks

        assertEquals(3, blocks.size)
        assertEquals(0x1000uL, blocks[0].start.virtualAddress)
        assertEquals(0x1000uL, blocks[0].endInclusive.virtualAddress)
        assertEquals(NativeXrefKind.Jump, blocks[0].outgoingXrefs.single().kind)
        assertEquals(0x1005uL, blocks[0].outgoingXrefs.single().target?.virtualAddress)
        assertEquals(0x1002uL, blocks[1].start.virtualAddress)
        assertEquals(0x1003uL, blocks[1].endInclusive.virtualAddress)
        assertEquals(0x1005uL, blocks[2].start.virtualAddress)
        assertEquals(0x1005uL, blocks[2].endInclusive.virtualAddress)
    }

    private fun readSnapshot(name: String): String =
        Path.of("src/test/data/llvm-objdump", name).toFile().readText()
}
