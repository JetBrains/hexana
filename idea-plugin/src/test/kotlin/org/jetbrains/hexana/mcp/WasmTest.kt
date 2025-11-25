package org.jetbrains.hexana.mcp

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import java.io.FileInputStream
import kotlin.io.path.Path

open class WasmTest: UsefulTestCase() {
    val dataPath: String = "src/test/data/mcp/memory"

    fun loadTestDataFile(): String {
        return FileInputStream(getPathToTestDataFile()).bufferedReader().use { it.readText() }
    }

    fun doTest(body: CommonByteBuffer.(WasmModule?) -> Unit) {
        val bytes = wat2wasm(loadTestDataFile())
        val commonByteBuffer = bytes.toCommonByteBuffer()
        val wasmModule = WasmParser().parse(commonByteBuffer)
        commonByteBuffer.body(wasmModule)

    }
    protected fun getPathToTestDataFile(): String {
        return Path("${dataPath}/${getTestName(true)}.wat").toAbsolutePath().toString()
    }
}