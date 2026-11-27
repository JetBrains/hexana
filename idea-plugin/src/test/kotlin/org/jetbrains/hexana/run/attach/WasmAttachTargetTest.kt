package org.jetbrains.hexana.run.attach

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildNodeJsRunArgs
import org.jetbrains.hexana.shared.buildWasmtimeGdbArgs
import java.io.File

class WasmAttachTargetTest : UsefulTestCase() {

    fun testWasmtimeGdbstubAsLaunchedByHexana() {
        val args = buildWasmtimeGdbArgs(WasmRunInput(wasmFilePath = "/w/app.wasm"), port = 4321).arguments
        assertEquals(
            WasmAttachTarget.GdbStub(WasmEngine.WASMTIME, "127.0.0.1", 4321, "/w/app.wasm"),
            detectWasmAttachTarget("wasmtime", args),
        )
    }

    fun testWasmtimeGdbstubSpellings() {
        assertEquals(
            WasmAttachTarget.GdbStub(WasmEngine.WASMTIME, "127.0.0.1", 1234, "app.wasm"),
            detectWasmAttachTarget("/usr/local/bin/wasmtime", listOf("run", "--gdbstub=0.0.0.0:1234", "app.wasm")),
        )
        assertEquals(
            WasmAttachTarget.GdbStub(WasmEngine.WASMTIME, "localhost", 9000, null),
            detectWasmAttachTarget("wasmtime.exe", listOf("run", "-g", "localhost:9000", "precompiled-module")),
        )
    }

    fun testWasmtimeGuestArgumentsAreNotRuntimeFlags() {
        assertNull(detectWasmAttachTarget("wasmtime", listOf("run", "app.wasm", "-g", "127.0.0.1:1234")))
    }

    fun testWasmtimeWithoutGdbstubIsIgnored() {
        assertNull(detectWasmAttachTarget("wasmtime", listOf("run", "app.wasm")))
        assertNull(detectWasmAttachTarget("wasmtime", listOf("run", "-g", "127.0.0.1:0", "app.wasm")))
    }

    fun testIwasmDebugServer() {
        assertEquals(
            WasmAttachTarget.GdbStub(WasmEngine.WAMR, "127.0.0.1", 1234, "/w/app.wasm"),
            detectWasmAttachTarget("iwasm", listOf("-g=127.0.0.1:1234", "/w/app.wasm")),
        )
        assertNull(detectWasmAttachTarget("iwasm", listOf("/w/app.wasm")))
    }

    fun testNodeInspectFlags() {
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

    fun testNodeWithoutReachableInspectorIsIgnored() {
        assertNull(detectWasmAttachTarget("node", listOf("server.js")))
        assertNull(detectWasmAttachTarget("node", listOf("--inspect=0", "server.js")))
        // Script arguments are not node options.
        assertNull(detectWasmAttachTarget("node", listOf("server.js", "--inspect")))
    }

    fun testNodeFindsModuleInHexanaInlineRunner() {
        val runner = buildNodeJsRunArgs(WasmRunInput(wasmFilePath = "/w/app.wasm")).arguments
        val target = detectWasmAttachTarget("node", listOf("--inspect=9230") + runner)
        assertEquals(WasmAttachTarget.NodeInspector("127.0.0.1", 9230, "/w/app.wasm"), target)
    }

    fun testChromeRemoteDebuggingPort() {
        assertEquals(
            WasmAttachTarget.ChromeDevTools(9222, null, null),
            detectWasmAttachTarget("Google Chrome", listOf("--remote-debugging-port=9222", "http://localhost:8080")),
        )
        assertEquals(
            WasmAttachTarget.ChromeDevTools(0, "/tmp/profile", null),
            detectWasmAttachTarget("chrome", listOf("--remote-debugging-port=0", "--user-data-dir=/tmp/profile")),
        )
    }

    fun testChromeHelpersAndUndiscoverablePortsAreIgnored() {
        assertNull(detectWasmAttachTarget("chrome", listOf("--type=renderer", "--remote-debugging-port=9222")))
        assertNull(detectWasmAttachTarget("chrome", listOf("--remote-debugging-port=0")))
        assertNull(detectWasmAttachTarget("chrome", listOf("http://localhost:8080")))
    }

    fun testParseHostPort() {
        assertEquals("127.0.0.1" to 1234, parseHostPort("1234", defaultHost = "127.0.0.1"))
        assertEquals("::1" to 1234, parseHostPort("[::1]:1234", defaultHost = "127.0.0.1"))
        assertEquals("127.0.0.1" to 1234, parseHostPort("0.0.0.0:1234", defaultHost = "127.0.0.1"))
        assertNull(parseHostPort("host:notaport", defaultHost = "127.0.0.1"))
        assertNull(parseHostPort("host:70000", defaultHost = "127.0.0.1"))
    }

    fun testProcessInfoArgsAreTokenized() {
        val info = ProcessInfo(42, "iwasm -g=127.0.0.1:1234 \"/my dir/app.wasm\"", "iwasm", "-g=127.0.0.1:1234 \"/my dir/app.wasm\"")
        assertEquals(
            WasmAttachTarget.GdbStub(WasmEngine.WAMR, "127.0.0.1", 1234, "/my dir/app.wasm"),
            detectWasmAttachTarget(info),
        )
    }

    fun testResolveModulePath() {
        val dir = FileUtil.createTempDirectory("wasm-attach", null)
        try {
            val module = File(dir, "app.wasm").apply { writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d)) }
            assertEquals(module.absolutePath, resolveModulePath(module.absolutePath, pid = -1, projectBasePath = null))
            assertEquals(module.absolutePath, resolveModulePath("app.wasm", pid = -1, projectBasePath = dir.path))
            assertNull(resolveModulePath("missing.wasm", pid = -1, projectBasePath = dir.path))
            assertNull(resolveModulePath(null, pid = -1, projectBasePath = dir.path))
        } finally {
            FileUtil.delete(dir)
        }
    }
}
