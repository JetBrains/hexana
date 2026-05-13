package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.disassembly.core.NativeDisassemblyConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LlvmObjdumpRealBinarySmokeTest {
    @Test
    fun `disassembles committed ELF executable fixture when llvm objdump is available`() {
        val candidate = LlvmObjdumpDiscovery.discover()
        assumeTrue("llvm-objdump is not available on PATH", candidate != null)
        val llvmObjdump = candidate ?: return

        val fixture = fixtureRoot().resolve("elf/sample.elf")
        assumeTrue("ELF executable fixture is missing: $fixture", fixture.isRegularFile())

        when (val probeResult = runBlocking { LlvmObjdumpProbe.probe(llvmObjdump) }) {
            is LlvmObjdumpProbeResult.Failure -> throw AssertionError("llvm-objdump probe failed: ${probeResult.message}")
            is LlvmObjdumpProbeResult.Success -> assertTrue(probeResult.versionLine.isNotBlank())
        }

        val runResult = runBlocking { LlvmObjdumpRunner.run(
            candidate = llvmObjdump,
            command = LlvmObjdumpCommands.disassembleExecutableSections(fixture),
        ) }

        val stdout = when (runResult) {
            is LlvmObjdumpRunResult.Failure -> throw AssertionError(
                "llvm-objdump failed for $fixture: ${runResult.message}\nstdout:\n${runResult.stdout}\nstderr:\n${runResult.stderr}"
            )
            is LlvmObjdumpRunResult.Success -> runResult.stdout
        }

        assertTrue(stdout.contains("Disassembly of section"))

        val parsed = LlvmObjdumpDisassemblyParser.parse(stdout)
        assertTrue(parsed.instructions.isNotEmpty())
        assertTrue(parsed.functions.any { it.name == "main" })
        assertTrue(parsed.instructions.any { it.bytes.isNotEmpty() })

        val native = LlvmObjdumpNativeDisassemblyBuilder.fromLlvmObjdump(parsed)
        assertEquals(NativeDisassemblyConfidence.BackendProvided, native.confidence)
        assertTrue(native.instructions.isNotEmpty())
        assertTrue(native.functions.any { it.name == "main" })
    }
}

private fun fixtureRoot(): Path {
    var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (current != null) {
        val candidate = current.resolve("parsing/native-fixtures/test-data")
        if (candidate.toFile().isDirectory) return candidate
        current = current.parent
    }
    error("Cannot find parsing/native-fixtures/test-data from ${System.getProperty("user.dir")}")
}
