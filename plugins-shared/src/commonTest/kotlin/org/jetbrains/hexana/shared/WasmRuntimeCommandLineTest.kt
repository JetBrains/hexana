package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
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
    // classifyDependencies
    // -----------------------------------------------------------------------

    @Test
    fun classifyDependenciesSkipsMissingFiles() {
        val deps = mapOf("missing" to "/nonexistent.wasm")
        val (core, component) = classifyDependencies(deps) { null }
        assertTrue(core.isEmpty())
        assertTrue(component.isEmpty())
    }
}
