package org.jetbrains.hexana.run.attach

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.WasmAttachRuntime
import org.jetbrains.hexana.shared.WasmAttachTarget
import java.io.File

/** IntelliJ-side attach plumbing; target detection is tested in plugins-shared. */
class WasmAttachDebuggerProviderTest : UsefulTestCase() {

    fun testProcessInfoArgsAreTokenized() {
        val info = ProcessInfo(42, "iwasm -g=127.0.0.1:1234 \"/my dir/app.wasm\"", "iwasm", "-g=127.0.0.1:1234 \"/my dir/app.wasm\"")
        assertEquals(
            WasmAttachTarget.GdbStub(WasmAttachRuntime.WAMR, "127.0.0.1", 1234, "/my dir/app.wasm"),
            detectWasmAttachTarget(info),
        )
    }

    fun testResolveModulePath() {
        val dir = FileUtil.createTempDirectory("wasm-attach", null)
        try {
            val spaced = File(dir, "my wasm").apply { mkdirs() }
            val module = File(spaced, "app.wasm").apply { writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d)) }
            assertEquals(module.absolutePath, resolveModulePath(listOf(module.absolutePath), pid = -1, projectBasePath = null))
            assertEquals(module.absolutePath, resolveModulePath(listOf("my wasm/app.wasm"), pid = -1, projectBasePath = dir.path))
            // A path `ps` split at its space: the longest candidate that exists wins.
            val candidates = listOf("${dir.path}/my wasm/app.wasm", "wasm/app.wasm")
            assertEquals(module.absolutePath, resolveModulePath(candidates, pid = -1, projectBasePath = dir.path))
            assertNull(resolveModulePath(listOf("missing.wasm"), pid = -1, projectBasePath = dir.path))
            assertNull(resolveModulePath(emptyList(), pid = -1, projectBasePath = dir.path))
        } finally {
            FileUtil.delete(dir)
        }
    }

    fun testFindsListeningEndpointsOfAProcess() {
        if (!SystemInfo.isMac && !SystemInfo.isLinux && !SystemInfo.isWindows) return
        // A child JVM that listens on an OS-chosen port and prints it.
        val java = File(System.getProperty("java.home"), "bin/java").path
        val source = FileUtil.createTempFile("Listen", ".java", true).apply {
            writeText(
                "public class Listen { public static void main(String[] a) throws Exception {" +
                    " var s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());" +
                    " System.out.println(s.getLocalPort()); System.out.flush(); Thread.sleep(30000); } }"
            )
        }
        val process = ProcessBuilder(java, source.path).redirectErrorStream(true).start()
        try {
            val port = process.inputStream.bufferedReader().readLine().trim().toInt()
            val endpoints = findListeningEndpoints(process.pid().toInt())
            assertTrue("expected port $port in $endpoints", endpoints.any { it.port == port })
        } finally {
            process.destroyForcibly()
        }
    }
}
