package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlvmObjdumpDiscoveryTest {
    @Test
    fun `orders explicit path before toolchain extra directories and PATH`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            explicitPath = "/custom/bin/llvm-objdump",
            toolchainDirectories = listOf(Path.of("/toolchain/configured/bin")),
            extraDirectories = listOf(Path.of("/toolchain/extra/bin")),
            pathEnv = "/usr/local/bin:/usr/bin",
            isWindows = false,
            pathSeparator = ":",
        )

        assertEquals(Path.of("/custom/bin/llvm-objdump"), candidates[0].path)
        assertEquals(LlvmObjdumpSource.ExplicitPath, candidates[0].source)
        assertEquals(Path.of("/toolchain/configured/bin/llvm-objdump"), candidates[1].path)
        assertEquals(LlvmObjdumpSource.ToolchainDirectory, candidates[1].source)
        assertEquals(Path.of("/toolchain/extra/bin/llvm-objdump"), candidates[2].path)
        assertEquals(LlvmObjdumpSource.ExtraDirectory, candidates[2].source)
        assertEquals(Path.of("/usr/local/bin/llvm-objdump"), candidates[3].path)
        assertEquals(LlvmObjdumpSource.PathEnvironment, candidates[3].source)
    }

    @Test
    fun `uses exe and bare executable names on Windows`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            extraDirectories = listOf(Path.of("/toolchain/bin")),
            pathEnv = null,
            isWindows = true,
        )

        assertEquals(Path.of("/toolchain/bin/llvm-objdump.exe"), candidates[0].path)
        assertEquals(Path.of("/toolchain/bin/llvm-objdump"), candidates[1].path)
    }

    @Test
    fun `deduplicates repeated candidate paths`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            explicitPath = "/usr/bin/llvm-objdump",
            pathEnv = "/usr/bin:/usr/bin",
            isWindows = false,
            pathSeparator = ":",
        )

        assertEquals(listOf(Path.of("/usr/bin/llvm-objdump")), candidates.map { it.path })
    }

    @Test
    fun `ignores blank path entries`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            explicitPath = " ",
            pathEnv = ":/usr/bin::",
            isWindows = false,
            pathSeparator = ":",
        )

        assertEquals(listOf(Path.of("/usr/bin/llvm-objdump")), candidates.map { it.path })
    }

    @Test
    fun `strips quotes from explicit and PATH entries`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            explicitPath = "\"/custom llvm/bin/llvm-objdump\"",
            pathEnv = "\"/Program Files/LLVM/bin\"",
            isWindows = true,
            pathSeparator = ";",
        )

        assertEquals(Path.of("/custom llvm/bin/llvm-objdump"), candidates[0].path)
        assertEquals(Path.of("/Program Files/LLVM/bin/llvm-objdump.exe"), candidates[1].path)
        assertEquals(Path.of("/Program Files/LLVM/bin/llvm-objdump"), candidates[2].path)
    }

    @Test
    fun `expands Windows style environment variables in PATH entries`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            pathEnv = "%LLVM_HOME%/bin",
            isWindows = true,
            pathSeparator = ";",
            environment = mapOf("LLVM_HOME" to "C:/LLVM"),
        )

        assertEquals(Path.of("C:/LLVM/bin/llvm-objdump.exe"), candidates[0].path)
        assertEquals(Path.of("C:/LLVM/bin/llvm-objdump"), candidates[1].path)
    }

    @Test
    fun `expands Windows style environment variables ignoring case`() {
        val candidates = LlvmObjdumpDiscovery.candidatePaths(
            pathEnv = "%programfiles%/LLVM/bin",
            isWindows = true,
            pathSeparator = ";",
            environment = mapOf("ProgramFiles" to "C:/Program Files"),
        )

        assertEquals(Path.of("C:/Program Files/LLVM/bin/llvm-objdump.exe"), candidates[0].path)
        assertEquals(Path.of("C:/Program Files/LLVM/bin/llvm-objdump"), candidates[1].path)
    }

    @Test
    fun `discover returns first executable candidate`() {
        val candidate = LlvmObjdumpDiscovery.discover(
            toolchainDirectories = listOf(Path.of("/toolchain/bin")),
            extraDirectories = listOf(Path.of("/first/bin"), Path.of("/second/bin")),
            pathEnv = null,
            isWindows = false,
            isExecutable = {
                it == Path.of("/toolchain/bin/llvm-objdump") || it == Path.of("/second/bin/llvm-objdump")
            },
        )

        assertEquals(Path.of("/toolchain/bin/llvm-objdump"), candidate?.path)
        assertEquals(LlvmObjdumpSource.ToolchainDirectory, candidate?.source)
    }

    @Test
    fun `executable candidates returns all executable candidates in order`() {
        val candidates = LlvmObjdumpDiscovery.executableCandidates(
            toolchainDirectories = listOf(Path.of("/toolchain/bin")),
            extraDirectories = listOf(Path.of("/first/bin"), Path.of("/second/bin")),
            pathEnv = null,
            isWindows = false,
            isExecutable = {
                it == Path.of("/toolchain/bin/llvm-objdump") || it == Path.of("/second/bin/llvm-objdump")
            },
        )

        assertEquals(
            listOf(Path.of("/toolchain/bin/llvm-objdump"), Path.of("/second/bin/llvm-objdump")),
            candidates.map { it.path },
        )
    }

    @Test
    fun `discover returns null when no candidate is executable`() {
        val candidate = LlvmObjdumpDiscovery.discover(
            pathEnv = "/usr/bin",
            isWindows = false,
            pathSeparator = ":",
            isExecutable = { false },
        )

        assertNull(candidate)
    }
}
