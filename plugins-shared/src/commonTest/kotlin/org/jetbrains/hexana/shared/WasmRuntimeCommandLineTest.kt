package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.analysis.WasmProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmRuntimeCommandLineTest {

    // -----------------------------------------------------------------------
    // splitOptions
    // -----------------------------------------------------------------------

    @Test
    fun splitOptionsEmpty() {
        assertEquals(emptyList(), splitOptions(""))
        assertEquals(emptyList(), splitOptions("   "))
    }

    @Test
    fun splitOptionsSimple() {
        assertEquals(listOf("--foo", "bar"), splitOptions("--foo bar"))
    }

    @Test
    fun splitOptionsQuoted() {
        assertEquals(listOf("--name", "hello world"), splitOptions("""--name "hello world""""))
    }

    @Test
    fun splitOptionsSingleQuoted() {
        assertEquals(listOf("--dir=/tmp/my dir"), splitOptions("--dir='/tmp/my dir'"))
    }

    @Test
    fun splitOptionsEscapedQuoteInDoubleQuotes() {
        // Input: --flag "\"value\"" → the escaped quotes produce literal " chars
        assertEquals(listOf("--flag", "\"value\""), splitOptions("""--flag "\"value\"""""))
    }

    @Test
    fun splitOptionsMixedQuotes() {
        assertEquals(
            listOf("--double", "hello world", "--single", "foo bar"),
            splitOptions("""--double "hello world" --single 'foo bar'"""),
        )
    }

    @Test
    fun splitOptionsSingleQuotesInsideDouble() {
        assertEquals(listOf("it's a test"), splitOptions(""""it's a test""""))
    }

    @Test
    fun splitOptionsDoubleQuotesInsideSingle() {
        assertEquals(listOf("""say "hello""""), splitOptions("""'say "hello"'"""))
    }

    // -----------------------------------------------------------------------
    // buildWasmtimeRunArgs – basic
    // -----------------------------------------------------------------------

    @Test
    fun wasmtimeMinimalArgs() {
        val input = WasmRunInput(wasmFilePath = "/path/to/module.wasm")
        val result = buildWasmtimeRunArgs(input)
        assertEquals(listOf("run", "/path/to/module.wasm"), result.arguments)
        assertNull(result.workingDirectory)
    }

    @Test
    fun wasmtimeWithOptions() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            runtimeOptions = "--cranelift-opt-level=2 --wasm-timeout 5s",
        )
        val result = buildWasmtimeRunArgs(input)
        assertEquals("run", result.arguments.first())
        assertTrue(result.arguments.contains("--cranelift-opt-level=2"))
        assertTrue(result.arguments.contains("--wasm-timeout"))
        assertTrue(result.arguments.contains("5s"))
        assertTrue(result.arguments.last() == "/mod.wasm")
    }

    @Test
    fun wasmtimeWithProgramArgs() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            programArguments = "arg1 arg2",
        )
        val result = buildWasmtimeRunArgs(input)
        assertEquals(listOf("run", "/mod.wasm", "arg1", "arg2"), result.arguments)
    }

    @Test
    fun wasmtimeWithWorkingDirectory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            workingDirectory = "/work/dir",
        )
        val result = buildWasmtimeRunArgs(input)
        assertEquals("/work/dir", result.workingDirectory)
    }

    @Test
    fun wasmtimeWithEffectiveWasmPath() {
        val input = WasmRunInput(
            wasmFilePath = "/original.wasm",
            effectiveWasmPath = "/composed.wasm",
        )
        val result = buildWasmtimeRunArgs(input)
        assertTrue(result.arguments.contains("/composed.wasm"))
        assertTrue(!result.arguments.contains("/original.wasm"))
    }

    @Test
    fun wasmtimeWithCoreModuleDeps() {
        val input = WasmRunInput(wasmFilePath = "/mod.wasm")
        val deps = mapOf("env" to "/deps/env.wasm", "math" to "/deps/math.wasm")
        val result = buildWasmtimeRunArgs(input, coreModuleDeps = deps)
        val preloadIndices = result.arguments.mapIndexedNotNull { i, s ->
            if (s == "--preload") i else null
        }
        assertEquals(2, preloadIndices.size)
        assertTrue(result.arguments.contains("env=/deps/env.wasm"))
        assertTrue(result.arguments.contains("math=/deps/math.wasm"))
    }

    // -----------------------------------------------------------------------
    // buildWasmtimeDebugArgs
    // -----------------------------------------------------------------------

    @Test
    fun wasmtimeDebugContainsDebugFlags() {
        val input = WasmRunInput(wasmFilePath = "/mod.wasm")
        val result = buildWasmtimeDebugArgs(input)
        assertTrue(result.arguments.contains("-D"))
        assertTrue(result.arguments.contains("debug-info"))
        assertTrue(result.arguments.contains("-O"))
        assertTrue(result.arguments.contains("opt-level=0"))
        assertEquals("run", result.arguments.first())
    }

    @Test
    fun wasmtimeDebugFlagsBeforeUserOptions() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            runtimeOptions = "--user-flag",
        )
        val result = buildWasmtimeDebugArgs(input)
        val debugInfoIndex = result.arguments.indexOf("debug-info")
        val userFlagIndex = result.arguments.indexOf("--user-flag")
        assertTrue(debugInfoIndex < userFlagIndex, "Debug flags should come before user options")
    }

    // -----------------------------------------------------------------------
    // buildWamrRunArgs
    // -----------------------------------------------------------------------

    @Test
    fun wamrMinimalArgs() {
        val input = WasmRunInput(wasmFilePath = "/path/to/module.wasm")
        val result = buildWamrRunArgs(input)
        assertEquals(listOf("/path/to/module.wasm"), result.arguments)
        assertNull(result.workingDirectory)
    }

    @Test
    fun wamrWithExportName() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            exportName = "my_func",
        )
        val result = buildWamrRunArgs(input)
        val fIndex = result.arguments.indexOf("-f")
        assertTrue(fIndex >= 0)
        assertEquals("my_func", result.arguments[fIndex + 1])
    }

    @Test
    fun wamrWithOptions() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            runtimeOptions = "--stack-size=65536",
        )
        val result = buildWamrRunArgs(input)
        assertTrue(result.arguments.contains("--stack-size=65536"))
    }

    @Test
    fun wamrWithProgramArgs() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            programArguments = "hello world",
        )
        val result = buildWamrRunArgs(input)
        assertTrue(result.arguments.contains("hello"))
        assertTrue(result.arguments.contains("world"))
    }

    // -----------------------------------------------------------------------
    // buildWamrDebugArgs
    // -----------------------------------------------------------------------

    @Test
    fun wamrDebugContainsDebugFlag() {
        val input = WasmRunInput(wasmFilePath = "/mod.wasm")
        val result = buildWamrDebugArgs(input, host = "127.0.0.1", port = 1234)
        assertTrue(result.commandLine.arguments.contains("-g=127.0.0.1:1234"))
        assertEquals("127.0.0.1", result.debugHost)
        assertEquals(1234, result.debugPort)
    }

    @Test
    fun wamrDebugCustomPort() {
        val input = WasmRunInput(wasmFilePath = "/mod.wasm")
        val result = buildWamrDebugArgs(input, host = "0.0.0.0", port = 5678)
        assertTrue(result.commandLine.arguments.contains("-g=0.0.0.0:5678"))
        assertEquals("0.0.0.0", result.debugHost)
        assertEquals(5678, result.debugPort)
    }

    @Test
    fun wamrDebugWithExportName() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            exportName = "entry",
        )
        val result = buildWamrDebugArgs(input)
        val args = result.commandLine.arguments
        val fIndex = args.indexOf("-f")
        assertTrue(fIndex >= 0)
        assertEquals("entry", args[fIndex + 1])
    }

    // -----------------------------------------------------------------------
    // buildGraalWasmRunArgs
    // -----------------------------------------------------------------------

    @Test
    fun graalWasmMinimalArgs() {
        val input = WasmRunInput(wasmFilePath = "/path/to/module.wasm")
        val result = buildGraalWasmRunArgs(input)
        assertEquals(listOf("/path/to/module.wasm"), result.arguments)
        assertNull(result.workingDirectory)
    }

    @Test
    fun graalWasmWithExportName() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            exportName = "add",
        )
        val result = buildGraalWasmRunArgs(input)
        assertEquals(listOf("--entry-point", "add", "/mod.wasm"), result.arguments)
    }

    @Test
    fun graalWasmWithProgramArgs() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            programArguments = "1 2 3",
        )
        val result = buildGraalWasmRunArgs(input)
        assertEquals(listOf("/mod.wasm", "1", "2", "3"), result.arguments)
    }

    @Test
    fun graalWasmWithWorkingDirectory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            workingDirectory = "/some/dir",
        )
        val result = buildGraalWasmRunArgs(input)
        assertEquals("/some/dir", result.workingDirectory)
    }

    // -----------------------------------------------------------------------
    // Proposal flags
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal valid Wasm module with a shared memory, which
     * triggers the [WasmProposal.Threads] proposal.
     *
     * Binary layout:
     *   magic + version (8 bytes)
     *   memory section (id=5): 1 memory with shared + max flags
     *     flags = 0x03 (has-max | shared), min = 1, max = 1
     */
    private fun buildSharedMemoryWasm(): ByteArray {
        val writer = WasmBinaryWriter()
        // Wasm magic
        writer.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        // Wasm version 1
        writer.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        // Memory section (id = 5)
        val memContent = WasmBinaryWriter()
        memContent.writeULEB128(1)    // count = 1 memory
        memContent.writeByte(0x03)    // flags: has-max(0x01) | shared(0x02)
        memContent.writeULEB128(1)    // min = 1 page
        memContent.writeULEB128(1)    // max = 1 page
        writer.writeSection(5, memContent.toByteArray())
        return writer.toByteArray()
    }

    /**
     * Builds a minimal valid Wasm module (just magic + version) that
     * uses no special proposals.
     */
    private fun buildMvpWasm(): ByteArray {
        val writer = WasmBinaryWriter()
        writer.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        writer.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        return writer.toByteArray()
    }

    @Test
    fun detectProposalsReturnsEmptyForNull() {
        assertTrue(detectProposals(null).isEmpty())
    }

    @Test
    fun detectProposalsReturnsEmptyForMvpModule() {
        val proposals = detectProposals(buildMvpWasm())
        assertFalse(WasmProposal.Threads in proposals)
    }

    @Test
    fun detectProposalsDetectsThreads() {
        val proposals = detectProposals(buildSharedMemoryWasm())
        assertTrue(WasmProposal.Threads in proposals)
    }

    @Test
    fun wasmtimeProposalFlagsEmptyForMvp() {
        val flags = wasmtimeProposalFlags(emptySet())
        assertTrue(flags.isEmpty())
    }

    @Test
    fun wasmtimeProposalFlagsIncludesThreads() {
        val flags = wasmtimeProposalFlags(setOf(WasmProposal.Threads))
        assertTrue(flags.contains("-W"))
        assertTrue(flags.contains("threads"))
        assertTrue(flags.contains("-S"))
    }

    @Test
    fun wamrProposalFlagsIncludesMaxThreads() {
        val flags = wamrProposalFlags(setOf(WasmProposal.Threads))
        assertTrue(flags.contains("--max-threads=4"))
    }

    @Test
    fun wamrProposalFlagsEmptyForMvp() {
        val flags = wamrProposalFlags(emptySet())
        assertTrue(flags.isEmpty())
    }

    @Test
    fun graalWasmProposalFlagsIncludesThreads() {
        val flags = graalWasmProposalFlags(setOf(WasmProposal.Threads))
        assertTrue(flags.contains("--experimental-options"))
        assertTrue(flags.contains("--wasm.Threads=true"))
    }

    @Test
    fun graalWasmProposalFlagsEmptyForMvp() {
        val flags = graalWasmProposalFlags(emptySet())
        assertTrue(flags.isEmpty())
    }

    @Test
    fun graalWasmContextOptionsIncludesThreads() {
        val options = graalWasmContextOptions(setOf(WasmProposal.Threads))
        assertTrue(options.any { it.first == "wasm.Threads" && it.second == "true" })
    }

    @Test
    fun graalWasmContextOptionsEmptyForMvp() {
        val options = graalWasmContextOptions(emptySet())
        assertTrue(options.isEmpty())
    }

    @Test
    fun wasmtimeRunArgsIncludesThreadsFlagsForSharedMemory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildWasmtimeRunArgs(input)
        assertTrue(result.arguments.contains("-W"), "Should contain -W flag")
        assertTrue(result.arguments.contains("threads"), "Should contain threads")
        assertTrue(result.arguments.contains("-S"), "Should contain -S flag")
    }

    @Test
    fun wasmtimeRunArgsNoProposalFlagsForMvp() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildMvpWasm(),
        )
        val result = buildWasmtimeRunArgs(input)
        // Should only have "run" and the wasm file path
        assertEquals(listOf("run", "/mod.wasm"), result.arguments)
    }

    @Test
    fun wasmtimeDebugArgsIncludesThreadsFlags() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildWasmtimeDebugArgs(input)
        assertTrue(result.arguments.contains("-W"), "Debug should contain -W flag")
        assertTrue(result.arguments.contains("threads"), "Debug should contain threads")
        assertTrue(result.arguments.contains("-S"), "Debug should contain -S flag")
    }

    @Test
    fun wamrRunArgsIncludesMaxThreadsForSharedMemory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildWamrRunArgs(input)
        assertTrue(result.arguments.contains("--max-threads=4"), "Should contain --max-threads=4")
    }

    @Test
    fun wamrRunArgsNoProposalFlagsForMvp() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildMvpWasm(),
        )
        val result = buildWamrRunArgs(input)
        assertEquals(listOf("/mod.wasm"), result.arguments)
    }

    @Test
    fun wamrDebugArgsIncludesMaxThreadsForSharedMemory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildWamrDebugArgs(input)
        assertTrue(result.commandLine.arguments.contains("--max-threads=4"), "Debug should contain --max-threads=4")
    }

    @Test
    fun graalWasmRunArgsIncludesThreadsFlagsForSharedMemory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildGraalWasmRunArgs(input)
        assertTrue(result.arguments.contains("--experimental-options"), "Should contain --experimental-options")
        assertTrue(result.arguments.contains("--wasm.Threads=true"), "Should contain --wasm.Threads=true")
    }

    @Test
    fun graalWasmRunArgsNoProposalFlagsForMvp() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildMvpWasm(),
        )
        val result = buildGraalWasmRunArgs(input)
        assertEquals(listOf("/mod.wasm"), result.arguments)
    }

    @Test
    fun proposalFlagsBeforeUserOptions() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
            runtimeOptions = "--custom-flag",
        )
        val result = buildWasmtimeRunArgs(input)
        val threadsIdx = result.arguments.indexOf("threads")
        val customIdx = result.arguments.indexOf("--custom-flag")
        assertTrue(threadsIdx < customIdx, "Proposal flags should come before user options")
    }

    @Test
    fun wamrProposalFlagsBeforeUserOptions() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
            runtimeOptions = "--custom-flag",
        )
        val result = buildWamrRunArgs(input)
        val maxThreadsIdx = result.arguments.indexOf("--max-threads=4")
        val customIdx = result.arguments.indexOf("--custom-flag")
        assertTrue(maxThreadsIdx >= 0, "Should contain --max-threads=4")
        assertTrue(maxThreadsIdx < customIdx, "WAMR proposal flags should come before user options")
    }

    @Test
    fun graalWasmProposalFlagsBeforeWasmFile() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildGraalWasmRunArgs(input)
        val threadsIdx = result.arguments.indexOf("--wasm.Threads=true")
        val wasmFileIdx = result.arguments.indexOf("/mod.wasm")
        assertTrue(threadsIdx >= 0, "Should contain --wasm.Threads=true")
        assertTrue(threadsIdx < wasmFileIdx, "GraalWasm proposal flags should come before wasm file path")
    }

    // -----------------------------------------------------------------------
    // classifyDependencies
    // -----------------------------------------------------------------------

    @Test
    fun classifyDependenciesSkipsMissingFiles() {
        val deps = mapOf("missing" to "/nonexistent.wasm")
        val (core, component) = classifyDependencies(deps) { null }
        assertTrue(core.isEmpty())
        assertTrue(component.isEmpty())
    }

    // -----------------------------------------------------------------------
    // stripWasmtimeInvoke
    // -----------------------------------------------------------------------

    @Test
    fun stripInvokeSimple() {
        assertEquals("", stripWasmtimeInvoke("--invoke add"))
    }

    @Test
    fun stripInvokeQuoted() {
        assertEquals("", stripWasmtimeInvoke("""--invoke "myFunc(1, 2)""""))
    }

    @Test
    fun stripInvokePreservesOtherOptions() {
        assertEquals(
            "-W unknown-imports-default",
            stripWasmtimeInvoke("-W unknown-imports-default --invoke add"),
        )
    }

    @Test
    fun stripInvokeQuotedPreservesOtherOptions() {
        assertEquals(
            "-W unknown-imports-default",
            stripWasmtimeInvoke("""-W unknown-imports-default --invoke "myFunc(1, 2)""""),
        )
    }

    @Test
    fun stripInvokeNoInvoke() {
        assertEquals("--stack-size=65536", stripWasmtimeInvoke("--stack-size=65536"))
    }

    @Test
    fun stripInvokeEmptyString() {
        assertEquals("", stripWasmtimeInvoke(""))
    }

    // -----------------------------------------------------------------------
    // WasmRuntimeResolver.buildRuntimeCommandLine – invoke stripping
    // -----------------------------------------------------------------------

    @Test
    fun buildRuntimeCommandLineWamrStripsInvoke() {
        val result = WasmRuntimeResolver.buildRuntimeCommandLine(
            runtime = "wamr",
            wasmFilePath = "/mod.wasm",
            exportName = "add",
            runtimeOptions = "--invoke add",
        )
        val args = result.arguments
        assertTrue(args.contains("-f"), "WAMR should have -f flag")
        assertTrue(args.contains("add"), "WAMR should have function name")
        assertTrue(!args.contains("--invoke"), "WAMR should not have --invoke flag")
    }

    @Test
    fun buildRuntimeCommandLineGraalWasmStripsInvoke() {
        val result = WasmRuntimeResolver.buildRuntimeCommandLine(
            runtime = "graalwasm",
            wasmFilePath = "/mod.wasm",
            exportName = "add",
            runtimeOptions = "--invoke add",
        )
        val args = result.arguments
        assertTrue(args.contains("--entry-point"), "GraalWasm should have --entry-point flag")
        assertTrue(!args.contains("--invoke"), "GraalWasm should not have --invoke flag")
    }

    @Test
    fun buildRuntimeCommandLineWasmtimeKeepsInvoke() {
        val result = WasmRuntimeResolver.buildRuntimeCommandLine(
            runtime = "wasmtime",
            wasmFilePath = "/mod.wasm",
            exportName = "add",
            runtimeOptions = "--invoke add",
        )
        val args = result.arguments
        assertTrue(args.contains("--invoke"), "Wasmtime should keep --invoke flag")
    }
}
