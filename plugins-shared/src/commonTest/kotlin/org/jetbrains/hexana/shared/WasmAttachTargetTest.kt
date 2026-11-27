package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmAttachTargetTest {

    @Test
    fun wasmtimeGdbstubAsLaunchedByHexana() {
        val args = buildWasmtimeGdbArgs(WasmRunInput(wasmFilePath = "/w/app.wasm"), port = 4321).arguments
        assertEquals(
            WasmAttachTarget.GdbStub(WasmAttachRuntime.WASMTIME, "127.0.0.1", 4321, "/w/app.wasm"),
            detectWasmAttachTarget("wasmtime", args),
        )
    }

    @Test
    fun wasmtimeGdbstubSpellings() {
        assertEquals(
            WasmAttachTarget.GdbStub(WasmAttachRuntime.WASMTIME, "127.0.0.1", 1234, "app.wasm"),
            detectWasmAttachTarget("/usr/local/bin/wasmtime", listOf("run", "--gdbstub=0.0.0.0:1234", "app.wasm")),
        )
        assertEquals(
            WasmAttachTarget.GdbStub(WasmAttachRuntime.WASMTIME, "localhost", 9000, null),
            detectWasmAttachTarget("wasmtime.exe", listOf("run", "-g", "localhost:9000", "precompiled-module")),
        )
    }

    @Test
    fun wasmtimeGuestArgumentsAreNotRuntimeFlags() {
        assertNull(detectWasmAttachTarget("wasmtime", listOf("run", "app.wasm", "-g", "127.0.0.1:1234")))
    }

    @Test
    fun wasmtimeWithoutGdbstubIsIgnored() {
        assertNull(detectWasmAttachTarget("wasmtime", listOf("run", "app.wasm")))
        assertNull(detectWasmAttachTarget("wasmtime", listOf("run", "-g", "127.0.0.1:0", "app.wasm")))
    }

    @Test
    fun iwasmDebugServer() {
        assertEquals(
            WasmAttachTarget.GdbStub(WasmAttachRuntime.WAMR, "127.0.0.1", 1234, "/w/app.wasm"),
            detectWasmAttachTarget("iwasm", listOf("-g=127.0.0.1:1234", "/w/app.wasm")),
        )
        assertNull(detectWasmAttachTarget("iwasm", listOf("/w/app.wasm")))
    }

    @Test
    fun nodeInspectFlags() {
        assertEquals(
            WasmAttachTarget.NodeInspector("127.0.0.1", NODE_DEFAULT_INSPECT_PORT, null),
            detectWasmAttachTarget("node", listOf("--inspect", "server.js")),
        )
        assertEquals(
            WasmAttachTarget.NodeInspector("localhost", 9333, null),
            detectWasmAttachTarget("node", listOf("--inspect-brk=localhost:9333", "server.js")),
        )
        assertEquals(
            WasmAttachTarget.NodeInspector("127.0.0.1", 9444, null),
            detectWasmAttachTarget("node.exe", listOf("--inspect-wait", "--inspect-port=9444", "server.js")),
        )
        assertEquals(
            WasmAttachTarget.NodeInspector("127.0.0.1", 9229, null),
            detectWasmAttachTarget("node", listOf("-r", "ts-node/register", "--inspect", "server.ts")),
        )
    }

    @Test
    fun nodeWithoutReachableInspectorIsIgnored() {
        assertNull(detectWasmAttachTarget("node", listOf("server.js")))
        assertNull(detectWasmAttachTarget("node", listOf("--inspect=0", "server.js")))
        // Script arguments are not node options.
        assertNull(detectWasmAttachTarget("node", listOf("server.js", "--inspect")))
    }

    @Test
    fun nodeFindsModuleInHexanaInlineRunner() {
        val runner = buildNodeJsRunArgs(WasmRunInput(wasmFilePath = "/w/app.wasm")).arguments
        val target = detectWasmAttachTarget("node", listOf("--inspect=9230") + runner)
        assertEquals(WasmAttachTarget.NodeInspector("127.0.0.1", 9230, "/w/app.wasm"), target)
    }

    @Test
    fun chromeRemoteDebuggingPort() {
        assertEquals(
            WasmAttachTarget.ChromeDevTools(9222, null, null),
            detectWasmAttachTarget("Google Chrome", listOf("--remote-debugging-port=9222", "http://localhost:8080")),
        )
        assertEquals(
            WasmAttachTarget.ChromeDevTools(0, "/tmp/profile", null),
            detectWasmAttachTarget("chrome", listOf("--remote-debugging-port=0", "--user-data-dir=/tmp/profile")),
        )
    }

    @Test
    fun chromeHelpersAndUndiscoverablePortsAreIgnored() {
        assertNull(detectWasmAttachTarget("chrome", listOf("--type=renderer", "--remote-debugging-port=9222")))
        assertNull(detectWasmAttachTarget("chrome", listOf("--remote-debugging-port=0")))
        assertNull(detectWasmAttachTarget("chrome", listOf("http://localhost:8080")))
    }

    @Test
    fun parseHostPort() {
        assertEquals("127.0.0.1" to 1234, parseHostPort("1234", defaultHost = "127.0.0.1"))
        assertEquals("::1" to 1234, parseHostPort("[::1]:1234", defaultHost = "127.0.0.1"))
        assertEquals("127.0.0.1" to 1234, parseHostPort("0.0.0.0:1234", defaultHost = "127.0.0.1"))
        assertNull(parseHostPort("host:notaport", defaultHost = "127.0.0.1"))
        assertNull(parseHostPort("host:70000", defaultHost = "127.0.0.1"))
    }

    @Test
    fun fullCommandLineIsTokenized() {
        assertEquals(
            WasmAttachTarget.GdbStub(WasmAttachRuntime.WAMR, "127.0.0.1", 1234, "/my dir/app.wasm"),
            detectWasmAttachTargetFromCommandLine("/opt/bin/iwasm -g=127.0.0.1:1234 \"/my dir/app.wasm\""),
        )
        assertNull(detectWasmAttachTargetFromCommandLine(""))
    }

    @Test
    fun gdbStubEndpoint() {
        assertEquals("127.0.0.1:1234", WasmAttachTarget.GdbStub(WasmAttachRuntime.WASMTIME, "127.0.0.1", 1234, null).gdbStubEndpoint)
        assertNull(WasmAttachTarget.NodeInspector("127.0.0.1", 9229, null).gdbStubEndpoint)
    }
}
