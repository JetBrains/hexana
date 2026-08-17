package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import java.net.ServerSocket

class WamrDebugCommandLineStateTest : UsefulTestCase() {

    fun testDebugFlagContainsHostAndPort() {
        val port = 19876
        val result = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = "/tmp/module.wasm",
            wasmBytes = null,
            programArguments = "",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "",
            host = WamrDebugCommandLineState.DEFAULT_DEBUG_HOST,
            port = port,
        )

        assertEquals(port, result.debugPort)
        assertEquals(WamrDebugCommandLineState.DEFAULT_DEBUG_HOST, result.debugHost)
        assertTrue(
            "Expected -g=${WamrDebugCommandLineState.DEFAULT_DEBUG_HOST}:$port in ${result.arguments}",
            result.arguments.contains("-g=${WamrDebugCommandLineState.DEFAULT_DEBUG_HOST}:$port"),
        )
    }

    fun testWasmFilePathIsLastBeforeProgramArgs() {
        val wasmPath = "/home/user/hello.wasm"
        val result = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = wasmPath,
            wasmBytes = null,
            programArguments = "",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "",
            port = 19877,
        )

        assertEquals(wasmPath, result.arguments.last())
    }

    fun testProgramArgumentsAppendedAfterWasmFile() {
        val wasmPath = "/tmp/calc.wasm"
        val result = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = wasmPath,
            wasmBytes = null,
            programArguments = "42 hello",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "",
            port = 19878,
        )

        val wasmIdx = result.arguments.indexOf(wasmPath)
        assertTrue("wasm file must be present", wasmIdx >= 0)
        assertEquals("42", result.arguments[wasmIdx + 1])
        assertEquals("hello", result.arguments[wasmIdx + 2])
    }

    fun testEphemeralPortIsPositiveAndMatchesDebugFlag() {
        val port = ServerSocket(0).use { it.localPort }
        val result = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = "/tmp/module.wasm",
            wasmBytes = null,
            programArguments = "",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "",
            port = port,
        )

        assertTrue("port must be positive", port > 0)
        assertEquals(port, result.debugPort)
        assertTrue(
            "arguments must contain -g=...:$port",
            result.arguments.contains("-g=${WamrDebugCommandLineState.DEFAULT_DEBUG_HOST}:$port"),
        )
    }

    fun testTwoConsecutiveEphemeralPortsAreFree() {
        val port1 = ServerSocket(0).use { it.localPort }
        val port2 = ServerSocket(0).use { it.localPort }
        assertTrue("both ports must be positive", port1 > 0 && port2 > 0)

        val r1 = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = "/tmp/m.wasm",
            wasmBytes = null,
            programArguments = "",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "",
            port = port1,
        )
        val r2 = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = "/tmp/m.wasm",
            wasmBytes = null,
            programArguments = "",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "",
            port = port2,
        )

        assertEquals(port1, r1.debugPort)
        assertEquals(port2, r2.debugPort)
        assertTrue(r1.arguments.contains("-g=${WamrDebugCommandLineState.DEFAULT_DEBUG_HOST}:$port1"))
        assertTrue(r2.arguments.contains("-g=${WamrDebugCommandLineState.DEFAULT_DEBUG_HOST}:$port2"))
    }

    fun testExportNameAddsMinusFFlag() {
        val result = WamrDebugCommandLineState.buildWamrArguments(
            wasmFilePath = "/tmp/module.wasm",
            wasmBytes = null,
            programArguments = "",
            workingDirectory = "",
            wamrOptions = "",
            exportName = "myFunc",
            port = 19879,
        )

        val fIdx = result.arguments.indexOf("-f")
        assertTrue("-f flag must be present when export name is set", fIdx >= 0)
        assertEquals("myFunc", result.arguments[fIdx + 1])
    }
}
