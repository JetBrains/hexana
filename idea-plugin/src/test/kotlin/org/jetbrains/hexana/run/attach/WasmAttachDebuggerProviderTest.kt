package org.jetbrains.hexana.run.attach

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.util.io.FileUtil
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
