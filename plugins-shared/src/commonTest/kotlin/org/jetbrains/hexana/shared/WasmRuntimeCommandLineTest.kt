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
        assertTrue(flags.contains("shared-memory"))
        assertTrue(flags.contains("-S"))
    }

    @Test
    fun wamrProposalFlagsEmptyForThreads() {
        val flags = wamrProposalFlags(setOf(WasmProposal.Threads))
        assertTrue(flags.isEmpty(), "WAMR thread support is compile-time; no CLI flags needed")
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
        assertTrue(flags.contains("--wasm.UseUnsafeMemory=true"))
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
        assertTrue(options.any { it.first == "wasm.UseUnsafeMemory" && it.second == "true" })
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
        assertTrue(result.arguments.contains("shared-memory"), "Should contain shared-memory")
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
        assertTrue(result.arguments.contains("shared-memory"), "Debug should contain shared-memory")
        assertTrue(result.arguments.contains("-S"), "Debug should contain -S flag")
    }

    @Test
    fun wamrRunArgsNoProposalFlagsForSharedMemory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildWamrRunArgs(input)
        assertEquals(listOf("/mod.wasm"), result.arguments, "WAMR should not emit proposal flags")
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
    fun wamrDebugArgsNoProposalFlagsForSharedMemory() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
        )
        val result = buildWamrDebugArgs(input)
        assertFalse(result.commandLine.arguments.contains("--max-threads=4"), "Debug should not contain --max-threads=4")
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
        assertTrue(result.arguments.contains("--wasm.UseUnsafeMemory=true"), "Should contain --wasm.UseUnsafeMemory=true")
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
    fun wamrUserOptionsStillWork() {
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildSharedMemoryWasm(),
            runtimeOptions = "--custom-flag",
        )
        val result = buildWamrRunArgs(input)
        val customIdx = result.arguments.indexOf("--custom-flag")
        assertTrue(customIdx >= 0, "User options should be present")
        assertTrue(customIdx < result.arguments.indexOf("/mod.wasm"), "User options should come before wasm file")
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
    // Wasmtime stub generation & unknown-imports-default interaction
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal Wasm module that **imports** a shared memory from
     * `"foo" "bar"` and two functions from other modules (`"wasi"` and
     * `"wasi_snapshot_preview1"`). This mirrors the structure of the
     * `wasi_threads_exit_main_busy.wat` reproduction case.
     *
     * Binary layout:
     *   magic + version
     *   type section (id=1): 2 func types
     *   import section (id=2): 3 imports (1 memory + 2 functions)
     */
    private fun buildImportedSharedMemoryWasm(): ByteArray {
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D)) // magic
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00)) // version

        // Type section: 2 func types
        val types = WasmBinaryWriter()
        types.writeULEB128(2) // count
        // type 0: (param i32) -> (result i32)
        types.writeByte(0x60); types.writeULEB128(1); types.writeByte(0x7F)
        types.writeULEB128(1); types.writeByte(0x7F)
        // type 1: (param i32) -> ()
        types.writeByte(0x60); types.writeULEB128(1); types.writeByte(0x7F)
        types.writeULEB128(0)
        w.writeSection(1, types.toByteArray())

        // Import section: 3 imports
        val imports = WasmBinaryWriter()
        imports.writeULEB128(3) // count
        // import 0: memory "foo" "bar" (shared, min=1, max=1)
        imports.writeName("foo"); imports.writeName("bar")
        imports.writeByte(0x02) // memory import
        imports.writeByte(0x03) // flags: has-max(0x01) | shared(0x02)
        imports.writeULEB128(1) // min
        imports.writeULEB128(1) // max
        // import 1: func "wasi" "thread-spawn" (type 0)
        imports.writeName("wasi"); imports.writeName("thread-spawn")
        imports.writeByte(0x00) // func import
        imports.writeULEB128(0) // type index 0
        // import 2: func "wasi_snapshot_preview1" "proc_exit" (type 1)
        imports.writeName("wasi_snapshot_preview1"); imports.writeName("proc_exit")
        imports.writeByte(0x00) // func import
        imports.writeULEB128(1) // type index 1
        w.writeSection(2, imports.toByteArray())

        return w.toByteArray()
    }

    /**
     * A fake [StubFileWriter] that records stub writes in memory,
     * returning predictable paths.
     */
    private class InMemoryStubFileWriter : StubFileWriter {
        val writtenStubs = mutableMapOf<String, ByteArray>()
        override fun createTempDir(prefix: String): String = "/tmp/$prefix"
        override fun writeFile(dir: String, fileName: String, bytes: ByteArray): String {
            writtenStubs[fileName] = bytes
            return "$dir/$fileName"
        }
    }

    @Test
    fun wasmtimeRunArgsUsesUnknownImportsDefaultForSharedMemoryModule() {
        // Reproduces the wasmtime "import of foo::bar defined twice" error:
        // wasmtime's --preload conflicts with any import mechanism, so we
        // must use -W unknown-imports-default exclusively and never preload.
        val stubWriter = InMemoryStubFileWriter()
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildImportedSharedMemoryWasm(),
        )
        val result = buildWasmtimeRunArgs(input, stubFileWriter = stubWriter)
        val args = result.arguments

        assertTrue(args.contains("unknown-imports-default"),
            "Should use unknown-imports-default for shared-memory modules")

        // Must NOT contain any --preload for stub modules
        val preloadPairs: List<String> = args.windowed(2).filter { it[0] == "--preload" }.map { it[1] }
        assertTrue(preloadPairs.isEmpty(), "Should not preload any stub modules")

        // No stubs should have been written
        assertTrue(stubWriter.writtenStubs.isEmpty(), "Should not write any stubs")
    }

    @Test
    fun wasmtimeDebugArgsUsesUnknownImportsDefaultForSharedMemoryModule() {
        val stubWriter = InMemoryStubFileWriter()
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = buildImportedSharedMemoryWasm(),
        )
        val result = buildWasmtimeDebugArgs(input, stubFileWriter = stubWriter)
        val args = result.arguments

        assertTrue(args.contains("unknown-imports-default"),
            "Debug should use unknown-imports-default for shared-memory modules")

        val preloadPairs: List<String> = args.windowed(2).filter { it[0] == "--preload" }.map { it[1] }
        assertTrue(preloadPairs.isEmpty(), "Debug should not preload any stub modules")
        assertTrue(stubWriter.writtenStubs.isEmpty(), "Debug should not write any stubs")
    }

    @Test
    fun wasmtimeRunArgsUsesUnknownImportsDefaultForFunctionOnlyImports() {
        // When a module has ONLY function imports (no memory/table/global/tag),
        // we should still use -W unknown-imports-default and not preload stubs.
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        val types = WasmBinaryWriter()
        types.writeULEB128(1)
        types.writeByte(0x60); types.writeULEB128(1); types.writeByte(0x7F); types.writeULEB128(0)
        w.writeSection(1, types.toByteArray())
        val imports = WasmBinaryWriter()
        imports.writeULEB128(1) // 1 function import
        imports.writeName("env"); imports.writeName("log")
        imports.writeByte(0x00); imports.writeULEB128(0)
        w.writeSection(2, imports.toByteArray())

        val stubWriter = InMemoryStubFileWriter()
        val input = WasmRunInput(
            wasmFilePath = "/mod.wasm",
            wasmBytes = w.toByteArray(),
        )
        val result = buildWasmtimeRunArgs(input, stubFileWriter = stubWriter)
        val args = result.arguments

        assertTrue(args.contains("unknown-imports-default"), "Should use unknown-imports-default for function-only imports")
        assertTrue(stubWriter.writtenStubs.isEmpty(), "Should not write any stubs")
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
