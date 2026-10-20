package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import org.jetbrains.hexana.providers.pe.PeFile
import org.jetbrains.hexana.providers.pe.PeImageFile
import org.jetbrains.hexana.providers.pe.PeParser
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ReadyToRun decoding of the `sample-r2r*` fixtures (`src/clr/r2r/build-r2r-fixtures.sh`), checked
 * against the perfmap crossgen2 wrote for each: every compiled method's start RVA, length and name.
 */
class ClrReadyToRunTest {
    private class Fixture(val clr: ClrMetadata, val image: ClrReadyToRunImage, val perfmap: Map<Long, Pair<Int, String>>)

    private fun fixture(name: String): Fixture {
        val dir = Path.of(System.getProperty("user.dir")).resolve("../native-fixtures/test-data/pe").normalize()
        val bytes = dir.resolve("$name.dll").readBytes().toCommonByteBuffer()
        val pe = assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(bytes)).value)
        val clr = assertNotNull(pe.clr)
        val image = assertNotNull(ClrReadyToRunParser.parse(bytes, clr, pe.coffHeader.machine, pe.optionalHeader, pe.sections))
        // "RVA LENGTH NAME" in hex; FFFFFFxx lines are perfmap metadata, not methods.
        val perfmap = dir.resolve("$name.perfmap.txt").readLines()
            .map { it.split(" ", limit = 3) }
            .filter { !it[0].startsWith("FFFFFF") }
            .associate { it[0].toLong(16) to (it[1].toInt(16) to it[2]) }
        return Fixture(clr, image, perfmap)
    }

    private fun assertMatchesPerfmap(name: String, os: String, machine: Int) {
        val (clr, image, perfmap) = fixture(name).let { Triple(it.clr, it.image, it.perfmap) }
        assertEquals(os, image.targetOs, name)
        assertEquals(machine, image.targetMachine, name)
        assertEquals(emptyList(), image.diagnostics, name)
        assertEquals(perfmap.size, image.methods.size, name)
        for (method in image.methods) {
            val begin = image.runtimeFunctions[method.firstRuntimeFunction].beginRva.toLong()
            val (length, perfName) = assertNotNull(perfmap[begin], "$name: no compiled method at 0x${begin.toString(16)}")
            val definition = clr.methods[assertNotNull(method.methodDefRow) - 1]
            val type = clr.typeDefs[definition.declaringTypeRow - 1]
            assertEquals(length, method.codeSize, "$name: $perfName")
            // Instantiations print their owner as e.g. `Box`1[System.Int32]`, so type and method are matched separately.
            assertTrue(type.name in perfName && "::${definition.name}(" in perfName, "$name: $perfName vs ${type.name}::${definition.name}")
        }
    }

    @Test
    fun `macOS ARM64 lengths come from xdata`() = assertMatchesPerfmap("sample-r2r", "macOS", 0xAA64)

    @Test
    fun `Windows x64 lengths come from the runtime function table`() = assertMatchesPerfmap("sample-r2r-win-x64", "Windows", 0x8664)

    @Test
    fun `Windows x86 lengths come from the GC info header`() = assertMatchesPerfmap("sample-r2r-win-x86", "Windows", 0x014C)

    @Test
    fun `Linux ARM lengths come from Thumb xdata`() = assertMatchesPerfmap("sample-r2r-linux-arm", "Linux", 0x01C4)

    @Test
    fun `funclets belong to their method and instantiations are named`() {
        val (clr, image, _) = fixture("sample-r2r-win-x64").let { Triple(it.clr, it.image, it.perfmap) }
        fun named(method: ClrReadyToRunMethod) = clr.methods[method.methodDefRow!! - 1].name
        // Parse's catch handler is a funclet: a second runtime function owned by the same method.
        assertEquals(2, image.methods.single { named(it) == "Parse" }.runtimeFunctionCount)
        assertEquals(
            setOf("Box<int>::.ctor", "Box<int>::ToString", "Box<Point>::.ctor", "Box<Point>::ToString", "Box<__Canon>::.ctor", "Box<__Canon>::ToString", "-::Max<__Canon>"),
            image.methods.filter { it.isInstantiation }
                .map { "${it.ownerType ?: "-"}::${named(it)}" + if (it.methodTypeArguments.isEmpty()) "" else it.methodTypeArguments.joinToString(", ", "<", ">") }
                .toSet(),
        )
        assertTrue(image.methods.all { it.foreignAssembly == null })
        assertEquals(
            listOf("ImportSections", "RuntimeFunctions", "MethodDefEntryPoints", "InstanceMethodEntryPoints"),
            image.sections.map { it.name }.filter { it in setOf("ImportSections", "RuntimeFunctions", "MethodDefEntryPoints", "InstanceMethodEntryPoints") },
        )
        assertEquals(1, image.exceptionInfo.size)
        assertTrue(image.importSections.any { it.signatureOffsets.isNotEmpty() })
    }

    @Test
    fun `machine overrides decode to OS and architecture`() {
        assertEquals("Windows" to 0x8664, ClrReadyToRunParser.target(0x8664))
        assertEquals("macOS" to 0xAA64, ClrReadyToRunParser.target(0xEC20))
        assertEquals("Linux" to 0x8664, ClrReadyToRunParser.target(0x8664 xor 0x7B79))
        assertEquals("unknown OS" to 0x1234, ClrReadyToRunParser.target(0x1234))
    }
}
